import * as fs from "node:fs";
import * as path from "node:path";
import { spawn } from "node:child_process";

import type { LifecycleHelperResult } from "../models/jvm_lifecycle.model";

const HELPER_JAR_ENV = "MCP_JAVA_ATTACH_HELPER_JAR";
const AGENT_JAR_ENV = "MCP_JAVA_AGENT_JAR";
const JAVA_BIN_ENV = "MCP_JAVA_BIN";
const HELPER_TIMEOUT_MS = 15_000;
const MAX_HELPER_OUTPUT_CHARS = 65_536;

export type LifecycleHelperLaunch = {
  helperJarAbs: string;
  javaBin: string;
};

function isRegularFile(candidate: string): boolean {
  try {
    return fs.statSync(candidate).isFile();
  } catch {
    return false;
  }
}

function looksLikeRepoRoot(candidate: string): boolean {
  return (
    isRegularFile(path.join(candidate, "package.json")) &&
    isRegularFile(path.join(candidate, "java-agent", "pom.xml"))
  );
}

function findRepoRoot(): string | undefined {
  const candidates = [path.resolve(process.cwd())];
  let cursor = path.resolve(__dirname);
  for (let index = 0; index < 10; index += 1) {
    candidates.push(cursor);
    const parent = path.dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }
  return candidates.find(looksLikeRepoRoot);
}

function resolveHelperJar(): string | undefined {
  const configured = process.env[HELPER_JAR_ENV]?.trim();
  if (configured && isRegularFile(configured)) {
    return path.resolve(configured);
  }

  const repoRoot = findRepoRoot();
  if (!repoRoot) return undefined;
  const targetDir = path.join(repoRoot, "java-agent", "core", "core-jvm-attach", "target");
  try {
    const candidates = fs
      .readdirSync(targetDir)
      .filter((name) => /^mcp-java-dev-tools-core-jvm-attach-.+\.jar$/u.test(name))
      .filter((name) => !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"))
      .sort((left, right) => right.localeCompare(left));
    const selected = candidates[0];
    return selected ? path.join(targetDir, selected) : undefined;
  } catch {
    return undefined;
  }
}

export function resolveLifecycleHelperLaunch():
  | { ok: true; value: LifecycleHelperLaunch }
  | { ok: false; reasonCode: "attach_helper_unavailable" } {
  const helperJarAbs = resolveHelperJar();
  if (!helperJarAbs) {
    return { ok: false, reasonCode: "attach_helper_unavailable" };
  }
  return {
    ok: true,
    value: {
      helperJarAbs,
      javaBin: process.env[JAVA_BIN_ENV]?.trim() || "java",
    },
  };
}

export function resolveAgentJar():
  | { ok: true; value: string }
  | { ok: false; reasonCode: "agent_artifact_unavailable" } {
  const configuredAgent = process.env[AGENT_JAR_ENV]?.trim();
  if (!configuredAgent || !isRegularFile(configuredAgent)) {
    return { ok: false, reasonCode: "agent_artifact_unavailable" };
  }
  return { ok: true, value: path.resolve(configuredAgent) };
}

function parseHelperResult(value: string): LifecycleHelperResult | undefined {
  try {
    const parsed = JSON.parse(value) as Partial<LifecycleHelperResult>;
    if (
      typeof parsed.operation !== "string" ||
      typeof parsed.outcome !== "string" ||
      typeof parsed.reasonCode !== "string" ||
      !Array.isArray(parsed.pids) ||
      !Array.isArray(parsed.nonRestorableClasses) ||
      !parsed.pids.every((pid) => typeof pid === "string") ||
      !parsed.nonRestorableClasses.every((className) => typeof className === "string")
    ) {
      return undefined;
    }
    return parsed as LifecycleHelperResult;
  } catch {
    return undefined;
  }
}

export async function runLifecycleHelper(
  launch: LifecycleHelperLaunch,
  args: string[],
): Promise<LifecycleHelperResult | { reasonCode: string }> {
  return await new Promise((resolve) => {
    const child = spawn(launch.javaBin, ["-jar", launch.helperJarAbs, ...args], {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    let stdout = "";
    let settled = false;
    const finish = (result: LifecycleHelperResult | { reasonCode: string }) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(result);
    };
    const timeout = setTimeout(() => {
      child.kill();
      finish({ reasonCode: "attach_helper_timeout" });
    }, HELPER_TIMEOUT_MS);

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      if (stdout.length < MAX_HELPER_OUTPUT_CHARS) {
        stdout += chunk.slice(0, MAX_HELPER_OUTPUT_CHARS - stdout.length);
      }
    });
    child.once("error", () => finish({ reasonCode: "attach_helper_spawn_failed" }));
    child.once("close", (exitCode) => {
      const parsed = parseHelperResult(stdout.trim());
      if (parsed) {
        finish(parsed);
        return;
      }
      finish({ reasonCode: exitCode === 0 ? "attach_helper_output_invalid" : "attach_helper_failed" });
    });
  });
}
