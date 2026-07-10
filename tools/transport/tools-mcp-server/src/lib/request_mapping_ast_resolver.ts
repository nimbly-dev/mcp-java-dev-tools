import * as fs from "node:fs/promises";
import * as path from "node:path";
import { spawn } from "node:child_process";

import { getContractVersion } from "@/config/contract-version";
import type {
  JvmAstRequestMappingInput,
  JvmAstRequestMappingResult,
} from "@tools-registry/models/synthesis/request_mapping_ast.model";

const DEFAULT_TIMEOUT_MS = 15_000;
const AST_RESOLVER_JAR_ENV = "MCP_JAVA_REQUEST_MAPPING_RESOLVER_JAR";
const AST_RESOLVER_CLASSPATH_ENV = "MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH";
const JAVA_BIN_ENV = "MCP_JAVA_BIN";
const CORE_REQUEST_MAPPER_MAIN_CLASS =
  "com.nimbly.mcpjavadevtools.requestmapping.RequestMappingResolverMain";

type ResolverLaunch = {
  args: string[];
  evidence: string[];
};

type VersionedJarCandidate = {
  repoRoot: string;
  jarAbs: string;
  version: string;
  variant: "all" | "plain";
};

async function fileExists(fileAbs: string): Promise<boolean> {
  try {
    await fs.access(fileAbs);
    return true;
  } catch {
    return false;
  }
}

async function looksLikeRepoRoot(dirAbs: string): Promise<boolean> {
  const packageJsonAbs = path.join(dirAbs, "package.json");
  const javaAgentPomAbs = path.join(dirAbs, "java-agent", "pom.xml");
  return (await fileExists(packageJsonAbs)) && (await fileExists(javaAgentPomAbs));
}

async function findRepoRoots(): Promise<string[]> {
  const out: string[] = [];
  const seen = new Set<string>();

  const add = async (candidateAbs: string) => {
    const normalized = path.resolve(candidateAbs);
    if (seen.has(normalized)) return;
    seen.add(normalized);
    if (await looksLikeRepoRoot(normalized)) {
      out.push(normalized);
    }
  };

  await add(process.cwd());

  let cursor = path.resolve(__dirname);
  for (let i = 0; i < 10; i += 1) {
    await add(cursor);
    const parent = path.dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }

  return out;
}

function compareVersionStringsDesc(left: string, right: string): number {
  const toTokens = (value: string): Array<number | string> => {
    const parts = value.match(/[0-9]+|[A-Za-z]+/g) ?? [];
    return parts.map((part) => {
      if (/^[0-9]+$/.test(part)) return Number.parseInt(part, 10);
      return part.toLowerCase();
    });
  };

  const leftTokens = toTokens(left);
  const rightTokens = toTokens(right);
  const length = Math.max(leftTokens.length, rightTokens.length);
  for (let i = 0; i < length; i += 1) {
    const leftToken = leftTokens[i];
    const rightToken = rightTokens[i];
    if (leftToken === undefined) return 1;
    if (rightToken === undefined) return -1;
    if (typeof leftToken === "number" && typeof rightToken === "number") {
      if (leftToken !== rightToken) return rightToken - leftToken;
      continue;
    }
    const leftText = String(leftToken);
    const rightText = String(rightToken);
    if (leftText !== rightText) return rightText.localeCompare(leftText);
  }
  return 0;
}

function compareCandidates(left: VersionedJarCandidate, right: VersionedJarCandidate): number {
  const versionCmp = compareVersionStringsDesc(left.version, right.version);
  if (versionCmp !== 0) return versionCmp;
  if (left.variant !== right.variant) return left.variant === "all" ? -1 : 1;
  return left.jarAbs.localeCompare(right.jarAbs);
}

async function collectVersionedJarCandidates(
  repoRoot: string,
  targetDirParts: string[],
  prefixes: string[],
): Promise<VersionedJarCandidate[]> {
  const targetDirAbs = path.join(repoRoot, ...targetDirParts);
  let entries: string[];
  try {
    entries = await fs.readdir(targetDirAbs);
  } catch {
    return [];
  }

  const out: VersionedJarCandidate[] = [];
  for (const filename of entries) {
    for (const prefix of prefixes) {
      const allMatch = filename.match(new RegExp(`^${prefix}-(.+)-all\\.jar$`));
      if (allMatch) {
        out.push({
          repoRoot,
          jarAbs: path.join(targetDirAbs, filename),
          version: allMatch[1] ?? "",
          variant: "all",
        });
        continue;
      }
      const plainMatch = filename.match(new RegExp(`^${prefix}-(.+)\\.jar$`));
      if (plainMatch && !filename.endsWith("-all.jar") && !filename.endsWith("-shaded.jar")) {
        out.push({
          repoRoot,
          jarAbs: path.join(targetDirAbs, filename),
          version: plainMatch[1] ?? "",
          variant: "plain",
        });
      }
    }
  }

  out.sort(compareCandidates);
  return out;
}

async function resolveLaunch(): Promise<ResolverLaunch | undefined> {
  const configuredClasspath = process.env[AST_RESOLVER_CLASSPATH_ENV]?.trim();
  if (configuredClasspath) {
    return {
      args: ["-cp", configuredClasspath, CORE_REQUEST_MAPPER_MAIN_CLASS],
      evidence: [`envClasspath=${configuredClasspath}`],
    };
  }

  const configured = process.env[AST_RESOLVER_JAR_ENV]?.trim();
  if (configured) {
    return {
      args: ["-jar", configured],
      evidence: [`envJarPath=${configured}`],
    };
  }

  const repoRoots = await findRepoRoots();
  if (repoRoots.length === 0) {
    return undefined;
  }

  const versionCandidates = Array.from(
    new Set([getContractVersion(), "0.1.0"].filter((v) => v && v !== "unknown")),
  );
  const mapperCoreCandidates: Array<{ repoRoot: string; jarAbs: string }> = [];
  const springPluginCandidates: Array<{ repoRoot: string; jarAbs: string }> = [];
  const legacyCandidates: Array<{ repoRoot: string; jarAbs: string }> = [];
  const scannedCoreMapperCandidates: VersionedJarCandidate[] = [];
  const scannedSpringPluginCandidates: VersionedJarCandidate[] = [];
  const scannedLegacyCandidates: VersionedJarCandidate[] = [];

  for (const repoRoot of repoRoots) {
    scannedCoreMapperCandidates.push(
      ...(await collectVersionedJarCandidates(
        repoRoot,
        ["java-agent", "core", "core-entrypoint-mapper", "target"],
        [
          "mcp-java-dev-tools-core-entrypoint-mapper",
          "mcp-java-dev-tools-core-request-mapper",
        ],
      )),
    );
    scannedSpringPluginCandidates.push(
      ...(await collectVersionedJarCandidates(
        repoRoot,
        ["java-agent", "mappers-adapters", "adapter-request-mapper-spring-http", "target"],
        [
          "mcp-java-dev-tools-adapter-request-mapper-spring-http",
          "mcp-java-dev-tools-adapter-request-mapper-spring",
        ],
      )),
    );
    scannedLegacyCandidates.push(
      ...(await collectVersionedJarCandidates(
        repoRoot,
        ["java-agent", "request-mapping-resolver", "target"],
        ["mcp-java-dev-tools-request-mapping-resolver"],
      )),
    );

    for (const version of versionCandidates) {
      mapperCoreCandidates.push(
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "core",
            "core-entrypoint-mapper",
            "target",
            `mcp-java-dev-tools-core-entrypoint-mapper-${version}-all.jar`,
          ),
        },
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "core",
            "core-entrypoint-mapper",
            "target",
            `mcp-java-dev-tools-core-entrypoint-mapper-${version}.jar`,
          ),
        },
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "core",
            "core-request-mapper",
            "target",
            `mcp-java-dev-tools-core-request-mapper-${version}-all.jar`,
          ),
        },
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "core",
            "core-request-mapper",
            "target",
            `mcp-java-dev-tools-core-request-mapper-${version}.jar`,
          ),
        },
      );
      springPluginCandidates.push(
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "mappers-adapters",
            "adapter-request-mapper-spring-http",
            "target",
            `mcp-java-dev-tools-adapter-request-mapper-spring-http-${version}.jar`,
          ),
        },
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "mappers-adapters",
            "adapter-request-mapper-spring-http",
            "target",
            `mcp-java-dev-tools-adapter-request-mapper-spring-${version}.jar`,
          ),
        },
      );
      legacyCandidates.push(
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "request-mapping-resolver",
            "target",
            `mcp-java-dev-tools-request-mapping-resolver-${version}-all.jar`,
          ),
        },
        {
          repoRoot,
          jarAbs: path.join(
            repoRoot,
            "java-agent",
            "request-mapping-resolver",
            "target",
            `mcp-java-dev-tools-request-mapping-resolver-${version}.jar`,
          ),
        },
      );
    }
  }

  let selectedCoreMapperJar: { repoRoot: string; jarAbs: string } | undefined;
  const sortedScannedCoreMapperCandidates = scannedCoreMapperCandidates.sort(compareCandidates);
  for (const candidate of sortedScannedCoreMapperCandidates) {
    if (await fileExists(candidate.jarAbs)) {
      selectedCoreMapperJar = { repoRoot: candidate.repoRoot, jarAbs: candidate.jarAbs };
      break;
    }
  }
  for (const candidate of mapperCoreCandidates) {
    if (selectedCoreMapperJar) break;
    if (await fileExists(candidate.jarAbs)) {
      selectedCoreMapperJar = candidate;
      break;
    }
  }
  if (selectedCoreMapperJar) {
    const classpathEntries = [selectedCoreMapperJar.jarAbs];
    let springPluginJar: string | undefined;
    const sortedScannedSpringPluginCandidates = scannedSpringPluginCandidates.sort(compareCandidates);
    for (const pluginCandidate of sortedScannedSpringPluginCandidates) {
      if (pluginCandidate.repoRoot !== selectedCoreMapperJar.repoRoot) {
        continue;
      }
      if (await fileExists(pluginCandidate.jarAbs)) {
        springPluginJar = pluginCandidate.jarAbs;
        classpathEntries.push(pluginCandidate.jarAbs);
        break;
      }
    }
    for (const pluginCandidate of springPluginCandidates) {
      if (springPluginJar) break;
      if (pluginCandidate.repoRoot !== selectedCoreMapperJar.repoRoot) {
        continue;
      }
      if (await fileExists(pluginCandidate.jarAbs)) {
        springPluginJar = pluginCandidate.jarAbs;
        classpathEntries.push(pluginCandidate.jarAbs);
        break;
      }
    }
    return {
      args: ["-cp", classpathEntries.join(path.delimiter), CORE_REQUEST_MAPPER_MAIN_CLASS],
      evidence: [
        `repoRoot=${selectedCoreMapperJar.repoRoot}`,
        `coreMapperJar=${selectedCoreMapperJar.jarAbs}`,
        `springPluginJar=${springPluginJar ?? "(missing)"}`,
      ],
    };
  }

  const sortedScannedLegacyCandidates = scannedLegacyCandidates.sort(compareCandidates);
  for (const candidate of sortedScannedLegacyCandidates) {
    if (await fileExists(candidate.jarAbs)) {
      return {
        args: ["-jar", candidate.jarAbs],
        evidence: [`repoRoot=${candidate.repoRoot}`, `legacyJarPath=${candidate.jarAbs}`],
      };
    }
  }
  for (const candidate of legacyCandidates) {
    if (await fileExists(candidate.jarAbs)) {
      return {
        args: ["-jar", candidate.jarAbs],
        evidence: [`repoRoot=${candidate.repoRoot}`, `legacyJarPath=${candidate.jarAbs}`],
      };
    }
  }
  return undefined;
}

function buildUnavailableFailure(
  reason: string,
  evidence: string[],
): JvmAstRequestMappingResult {
  return {
    status: "report",
    contractVersion: getContractVersion(),
    reasonCode: "ast_resolver_unavailable",
    failedStep: "request_mapping_resolver_bootstrap",
    nextAction:
      "Build the JVM request-mapping resolver artifacts or set MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH / MCP_JAVA_REQUEST_MAPPING_RESOLVER_JAR, then rerun route_synthesis with action=create_recipe.",
    evidence: [reason, ...evidence],
    attemptedStrategies: ["java_ast_resolver_bootstrap"],
  };
}

export async function resolveRequestMappingAst(
  input: JvmAstRequestMappingInput,
): Promise<JvmAstRequestMappingResult> {
  const launch = await resolveLaunch();
  if (!launch) {
    const repoRoots = await findRepoRoots();
    return buildUnavailableFailure("resolver_jar_missing=true", [
      `envJarPath=${process.env[AST_RESOLVER_JAR_ENV] ?? "(unset)"}`,
      `envClasspath=${process.env[AST_RESOLVER_CLASSPATH_ENV] ?? "(unset)"}`,
      `cwd=${process.cwd()}`,
      `resolverDir=${__dirname}`,
      `detectedRepoRoots=${repoRoots.length > 0 ? repoRoots.join("|") : "(none)"}`,
    ]);
  }

  const javaBin = process.env[JAVA_BIN_ENV]?.trim() || "java";

  return await new Promise<JvmAstRequestMappingResult>((resolve) => {
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
      resolve(
        buildUnavailableFailure("resolver_process_timeout=true", [
          `launchArgs=${launch.args.join(" ")}`,
          `timeoutMs=${DEFAULT_TIMEOUT_MS}`,
          ...launch.evidence,
        ]),
      );
    }, DEFAULT_TIMEOUT_MS);

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });

    child.on("error", (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(
        buildUnavailableFailure("resolver_process_spawn_failed=true", [
          `javaBin=${javaBin}`,
          `launchArgs=${launch.args.join(" ")}`,
          `error=${err.message}`,
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
          buildUnavailableFailure("resolver_process_nonzero_exit=true", [
            `exitCode=${String(code)}`,
            `launchArgs=${launch.args.join(" ")}`,
            `stderr=${stderr.trim() || "(empty)"}`,
            ...launch.evidence,
          ]),
        );
        return;
      }

      try {
        const parsed = JSON.parse(stdout.trim()) as JvmAstRequestMappingResult;
        resolve(parsed);
      } catch (err) {
        resolve(
          buildUnavailableFailure("resolver_output_invalid_json=true", [
            `launchArgs=${launch.args.join(" ")}`,
            `stdout=${stdout.trim() || "(empty)"}`,
            `stderr=${stderr.trim() || "(empty)"}`,
            `error=${err instanceof Error ? err.message : String(err)}`,
            ...launch.evidence,
          ]),
        );
      }
    });

    child.stdin.end(JSON.stringify(input));
  });
}

