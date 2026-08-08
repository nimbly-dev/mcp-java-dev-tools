import * as fs from "node:fs";
import * as path from "node:path";
import { spawn, type ChildProcess } from "node:child_process";

import type { JvmCandidate, LifecycleHelperResult } from "../models/jvm_lifecycle.model";

const HELPER_JAR_ENV = "MCP_JAVA_ATTACH_HELPER_JAR";
const AGENT_JAR_ENV = "MCP_JAVA_AGENT_JAR";
const JAVA_BIN_ENV = "MCP_JAVA_BIN";
const HELPER_INITIAL_TIMEOUT_MS = 15_000;
const ATTACH_RECONCILIATION_TIMEOUT_MS = 15_000;
const MAX_HELPER_OUTPUT_CHARS = 65_536;

export type LifecycleHelperLaunch = {
  helperJarAbs: string;
  javaBin: string;
};

export type LifecycleHelperRunOptions = {
  initialTimeoutMs?: number;
  attachReconciliationTimeoutMs?: number;
  spawnHelper?: (launch: LifecycleHelperLaunch, args: string[]) => LifecycleHelperChild;
};

export type LifecycleHelperChild = Pick<ChildProcess, "kill" | "once"> & {
  stdout: NonNullable<ChildProcess["stdout"]>;
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
      !Array.isArray(parsed.candidates) ||
      !Array.isArray(parsed.nonRestorableClasses) ||
      !parsed.pids.every((pid) => typeof pid === "string") ||
      !parsed.candidates.every(isJvmCandidate) ||
      !parsed.nonRestorableClasses.every((className) => typeof className === "string")
    ) {
      return undefined;
    }
    return parsed as LifecycleHelperResult;
  } catch {
    return undefined;
  }
}

function isJvmCandidate(value: unknown): value is JvmCandidate {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<JvmCandidate>;
  return (
    typeof candidate.pid === "string" &&
    /^[1-9][0-9]*$/u.test(candidate.pid) &&
    (typeof candidate.identityHint === "string" || candidate.identityHint === null) &&
    (candidate.identityHint === null || candidate.identityHint.length <= 128) &&
    (candidate.identitySource === "sanitized_attach_descriptor" ||
      candidate.identitySource === "sanitized_executable_basename" ||
      candidate.identitySource === "unavailable") &&
    (candidate.frameworkHint === "spring_boot_candidate" ||
      candidate.frameworkHint === "unknown") &&
    Array.isArray(candidate.frameworkEvidence) &&
    candidate.frameworkEvidence.length <= 4 &&
    candidate.frameworkEvidence.every(
      (evidence) => evidence === "spring_boot_launcher" || evidence === "executable_jar_name",
    ) &&
    (typeof candidate.processStartEpochMs === "number" || candidate.processStartEpochMs === null) &&
    (candidate.processStartEpochMs === null ||
      (Number.isSafeInteger(candidate.processStartEpochMs) && candidate.processStartEpochMs > 0))
  );
}

export async function runLifecycleHelper(
  launch: LifecycleHelperLaunch,
  args: string[],
  options: LifecycleHelperRunOptions = {},
): Promise<LifecycleHelperResult | { reasonCode: string }> {
  return await new Promise((resolve) => {
    let child: LifecycleHelperChild;
    try {
      child =
        options.spawnHelper?.(launch, args) ??
        spawn(launch.javaBin, ["-jar", launch.helperJarAbs, ...args], {
          stdio: ["ignore", "pipe", "pipe"],
          windowsHide: true,
        });
    } catch {
      resolve({ reasonCode: "attach_helper_spawn_failed" });
      return;
    }
    let stdout = "";
    let settled = false;
    let attachReconciliationTimeout: NodeJS.Timeout | undefined;
    const finish = (result: LifecycleHelperResult | { reasonCode: string }) => {
      if (settled) return;
      settled = true;
      clearTimeout(initialTimeout);
      if (attachReconciliationTimeout) clearTimeout(attachReconciliationTimeout);
      resolve(result);
    };
    const initialTimeoutMs = options.initialTimeoutMs ?? HELPER_INITIAL_TIMEOUT_MS;
    const attachReconciliationTimeoutMs =
      options.attachReconciliationTimeoutMs ?? ATTACH_RECONCILIATION_TIMEOUT_MS;
    const initialTimeout = setTimeout(() => {
      if (args[0] === "attach") {
        attachReconciliationTimeout = setTimeout(() => {
          child.kill();
          finish({ reasonCode: "attach_helper_timeout" });
        }, attachReconciliationTimeoutMs);
        return;
      }
      child.kill();
      finish({ reasonCode: "attach_helper_timeout" });
    }, initialTimeoutMs);

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
      finish({
        reasonCode: exitCode === 0 ? "attach_helper_output_invalid" : "attach_helper_failed",
      });
    });
  });
}
