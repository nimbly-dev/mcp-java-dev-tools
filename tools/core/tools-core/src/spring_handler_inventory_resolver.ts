import { spawn } from "node:child_process";
import * as fs from "node:fs/promises";
import * as path from "node:path";

import type {
  JvmAstHandlerInventoryInput,
  JvmAstHandlerInventoryResult,
  JvmAstRequestMappingFailure,
} from "@tools-registry/models/synthesis/request_mapping_ast.model";

const DEFAULT_TIMEOUT_MS = 15_000;
const JAVA_BIN_ENV = "MCP_JAVA_BIN";
const RESOLVER_JAR_ENV = "MCP_JAVA_REQUEST_MAPPING_RESOLVER_JAR";
const RESOLVER_CLASSPATH_ENV = "MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH";
const RESOLVER_MAIN_CLASS = "com.nimbly.mcpjavadevtools.requestmapping.RequestMappingResolverMain";

type ResolverLaunch = {
  args: string[];
  evidence: string[];
};

async function exists(fileAbs: string): Promise<boolean> {
  try {
    await fs.access(fileAbs);
    return true;
  } catch {
    return false;
  }
}

async function findRepositoryRoot(startAbs: string): Promise<string | undefined> {
  let current = path.resolve(startAbs);
  for (let depth = 0; depth < 12; depth += 1) {
    if (
      (await exists(path.join(current, "package.json"))) &&
      (await exists(path.join(current, "java-agent", "pom.xml")))
    ) {
      return current;
    }
    const parent = path.dirname(current);
    if (parent === current) return undefined;
    current = parent;
  }
  return undefined;
}

async function findRepositoryRoots(): Promise<string[]> {
  const candidates = await Promise.all([
    findRepositoryRoot(process.cwd()),
    findRepositoryRoot(__dirname),
  ]);
  return candidates.filter(
    (candidate, index, values): candidate is string =>
      typeof candidate === "string" && values.indexOf(candidate) === index,
  );
}

async function findLatestJar(targetDirAbs: string, prefix: string): Promise<string | undefined> {
  let files: string[];
  try {
    files = await fs.readdir(targetDirAbs);
  } catch {
    return undefined;
  }
  const candidates = files
    .filter((file) => file.startsWith(`${prefix}-`) && file.endsWith(".jar"))
    .filter((file) => !file.endsWith("-shaded.jar"))
    .sort((left, right) => right.localeCompare(left));
  const runnableCandidates = candidates.filter((file) => file.endsWith("-all.jar"));
  const selected = runnableCandidates.length > 0 ? runnableCandidates[0] : candidates[0];
  return selected ? path.join(targetDirAbs, selected) : undefined;
}

async function resolveLaunch(): Promise<ResolverLaunch | undefined> {
  const configuredClasspath = process.env[RESOLVER_CLASSPATH_ENV]?.trim();
  if (configuredClasspath) {
    return {
      args: ["-cp", configuredClasspath, RESOLVER_MAIN_CLASS],
      evidence: [`envClasspath=${configuredClasspath}`],
    };
  }
  const configuredJar = process.env[RESOLVER_JAR_ENV]?.trim();
  if (configuredJar) {
    return { args: ["-jar", configuredJar], evidence: [`envJarPath=${configuredJar}`] };
  }

  for (const repoRoot of await findRepositoryRoots()) {
    const coreJar = await findLatestJar(
      path.join(repoRoot, "java-agent", "core", "core-entrypoint-mapper", "target"),
      "mcp-java-dev-tools-core-entrypoint-mapper",
    );
    if (!coreJar) continue;
    const springJar = await findLatestJar(
      path.join(
        repoRoot,
        "java-agent",
        "mappers-adapters",
        "adapter-request-mapper-spring-http",
        "target",
      ),
      "mcp-java-dev-tools-adapter-request-mapper-spring-http",
    );
    const entries = springJar ? [coreJar, springJar] : [coreJar];
    return {
      args: ["-cp", entries.join(path.delimiter), RESOLVER_MAIN_CLASS],
      evidence: [
        `repoRoot=${repoRoot}`,
        `coreMapperJar=${coreJar}`,
        `springPluginJar=${springJar ?? "(missing)"}`,
      ],
    };
  }
  return undefined;
}

function unavailable(reason: string, evidence: string[]): JvmAstRequestMappingFailure {
  return {
    status: "report",
    contractVersion: "unknown",
    reasonCode: "ast_resolver_unavailable",
    failedStep: "request_mapping_resolver_bootstrap",
    nextAction:
      "Build the JVM request-mapping resolver artifacts or configure MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH / MCP_JAVA_REQUEST_MAPPING_RESOLVER_JAR.",
    evidence: [reason, ...evidence],
    attemptedStrategies: ["java_ast_resolver_bootstrap"],
  };
}

async function runResolver(
  input: JvmAstHandlerInventoryInput,
): Promise<JvmAstHandlerInventoryResult> {
  const launch = await resolveLaunch();
  if (!launch) {
    return unavailable("resolver_jar_missing=true", [
      `envJarPath=${process.env[RESOLVER_JAR_ENV] ?? "(unset)"}`,
      `envClasspath=${process.env[RESOLVER_CLASSPATH_ENV] ?? "(unset)"}`,
    ]);
  }
  const javaBin = process.env[JAVA_BIN_ENV]?.trim() || "java";
  return await new Promise((resolve) => {
    const child = spawn(javaBin, launch.args, {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill();
      resolve(unavailable("resolver_process_timeout=true", launch.evidence));
    }, DEFAULT_TIMEOUT_MS);
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(
        unavailable("resolver_process_spawn_failed=true", [
          `error=${error.message}`,
          ...launch.evidence,
        ]),
      );
    });
    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (code !== 0) {
        resolve(
          unavailable("resolver_process_nonzero_exit=true", [
            `stderr=${stderr.trim()}`,
            ...launch.evidence,
          ]),
        );
        return;
      }
      try {
        resolve(JSON.parse(stdout.trim()) as JvmAstHandlerInventoryResult);
      } catch (error) {
        resolve(
          unavailable("resolver_output_invalid_json=true", [
            `stdout=${stdout.trim()}`,
            `stderr=${stderr.trim()}`,
            `error=${error instanceof Error ? error.message : String(error)}`,
            ...launch.evidence,
          ]),
        );
      }
    });
    child.stdin.end(JSON.stringify({ action: "discover_handlers", ...input }));
  });
}

export async function resolveSpringHandlerInventory(
  input: JvmAstHandlerInventoryInput,
): Promise<JvmAstHandlerInventoryResult> {
  return await runResolver(input);
}
