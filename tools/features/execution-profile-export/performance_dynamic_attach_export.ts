import { promises as fs } from "node:fs";
import * as fsSync from "node:fs";
import path from "node:path";

import { CONFIG_DEFAULTS } from "@tools-core/probe_defaults";
import { loadProbeRegistry } from "@tools-core/probe-registry";
import { isAllowedProbeHost } from "@tools-feature-jvm-lifecycle";

import { asString, escapePsSingleQuoted, escapeShSingleQuoted, isRecord } from "./common";

const HELPER_ARTIFACT_ENV = "MCP_JAVA_ATTACH_HELPER_JAR";
const AGENT_ARTIFACT_ENV = "MCP_JAVA_AGENT_JAR";
const CONFIG_FILE_NAME = "portable-sidecar-attach.config.json";
const RUNNER_FILE_NAME = "run-portable-sidecar-lifecycle.js";
const EVIDENCE_FILE_NAME = "portable-sidecar.lifecycle.json";
const MAX_PATTERN_LENGTH = 2_048;

export type PortableDynamicAttachExport = {
  attachPs1Section: string[];
  cleanupPs1Section: string[];
  attachShSection: string[];
};

type ResolvedPortableDynamicAttach = {
  runtimeContextName: string;
  startupName: string;
  probeId: string;
  probeHost: string;
  probePort: number;
  include?: string;
  exclude?: string;
};

function hasUnsafePatternCharacter(value: string): boolean {
  return value.includes(",") || value.includes(";") || value.includes("=");
}

function isSafeRelativeJarPath(value: string): boolean {
  return (
    value.trim().length > 0 &&
    !path.isAbsolute(value) &&
    !path.win32.isAbsolute(value) &&
    !value.split(/[\\/]/u).includes("..")
  );
}

function resolveRuntimeContext(input: {
  workspace: Record<string, unknown> | undefined;
  runtimeContextName?: string;
}): Record<string, unknown> | undefined {
  const contexts = Array.isArray(input.workspace?.runtimeContexts)
    ? input.workspace.runtimeContexts.filter(isRecord)
    : [];
  if (input.runtimeContextName) {
    return contexts.find((entry) => asString(entry.name) === input.runtimeContextName);
  }
  const dynamic = contexts.filter(
    (entry) => isRecord(entry.sidecarLifecycle) && entry.sidecarLifecycle.activation === "dynamic_attach_local",
  );
  return dynamic.length === 1 ? dynamic[0] : undefined;
}

function validateDirectJavaStartup(input: {
  runtimeContext: Record<string, unknown>;
  runtimeContextName?: string;
}): { startupName: string; policy: Record<string, unknown> } {
  if (
    input.runtimeContext.mode !== "terminal" ||
    input.runtimeContext.autoStart !== true ||
    input.runtimeContext.autoStopOnFinish !== true
  ) {
    throw new Error("portable_dynamic_attach_runtime_invalid");
  }
  const policy = isRecord(input.runtimeContext.sidecarLifecycle)
    ? input.runtimeContext.sidecarLifecycle
    : undefined;
  if (
    !policy ||
    policy.activation !== "dynamic_attach_local" ||
    policy.verifyProbeAfterAttach !== true ||
    !asString(policy.targetStartupName) ||
    !asString(policy.probeId)
  ) {
    throw new Error("portable_dynamic_attach_runtime_invalid");
  }
  const startups = Array.isArray(input.runtimeContext.startups)
    ? input.runtimeContext.startups.filter(isRecord)
    : [];
  if (startups.length !== 1) {
    throw new Error("portable_dynamic_attach_runtime_invalid");
  }
  const startup = startups[0];
  if (!startup || asString(startup.name) !== asString(policy.targetStartupName)) {
    throw new Error("portable_dynamic_attach_runtime_invalid");
  }
  const command = asString(startup.command)?.toLowerCase();
  if (command !== "java" && command !== "java.exe") {
    throw new Error("portable_dynamic_attach_startup_not_direct_java");
  }
  const args = Array.isArray(startup.args)
    ? startup.args.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
    : [];
  const jarIndexes = args
    .map((entry, index) => (entry === "-jar" ? index : -1))
    .filter((index) => index >= 0);
  const jarIndex = jarIndexes[0];
  const jarPath = typeof jarIndex === "number" ? args[jarIndex + 1] : undefined;
  if (
    jarIndexes.length !== 1 ||
    typeof jarIndex !== "number" ||
    !jarPath ||
    !isSafeRelativeJarPath(jarPath) ||
    args.slice(0, jarIndex).some((entry) => !entry.startsWith("-"))
  ) {
    throw new Error("portable_dynamic_attach_startup_not_direct_java");
  }
  return { startupName: asString(startup.name) as string, policy };
}

function resolveProbeAttachConfig(input: {
  workspaceRootAbs: string;
  policy: Record<string, unknown>;
}): Omit<ResolvedPortableDynamicAttach, "runtimeContextName" | "startupName"> {
  const probeId = asString(input.policy.probeId);
  if (!probeId) throw new Error("portable_dynamic_attach_runtime_invalid");
  let registry;
  try {
    registry = loadProbeRegistry({
      filePath: path.join(input.workspaceRootAbs, ".mcpjvm", "probe-config.json"),
      workspaceRootAbs: input.workspaceRootAbs,
    });
  } catch {
    throw new Error("probe_registry_unavailable");
  }
  const probe = registry.probesById.get(probeId);
  if (!probe) throw new Error("probe_id_unknown");
  let parsed: URL;
  try {
    parsed = new URL(probe.baseUrl);
  } catch {
    throw new Error("probe_base_url_invalid");
  }
  const origin = parsed.origin;
  if (
    parsed.protocol !== "http:" ||
    (probe.baseUrl !== origin && probe.baseUrl !== `${origin}/`) ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== "/" ||
    parsed.search ||
    parsed.hash ||
    !parsed.port
  ) {
    throw new Error("probe_base_url_invalid");
  }
  const probeHost = parsed.hostname.replace(/^\[|\]$/gu, "");
  const probePort = Number(parsed.port);
  if (
    !probeHost ||
    !Number.isInteger(probePort) ||
    probePort <= 0 ||
    probePort > 65_535 ||
    !isAllowedProbeHost(probeHost)
  ) {
    throw new Error("probe_host_not_allowed");
  }
  const include = probe.include.map((entry) => entry.trim());
  const exclude = probe.exclude.map((entry) => entry.trim());
  if (
    include.some(
      (entry) => entry.length === 0 || entry.length > MAX_PATTERN_LENGTH || hasUnsafePatternCharacter(entry),
    ) ||
    exclude.some(
      (entry) => entry.length === 0 || entry.length > MAX_PATTERN_LENGTH || hasUnsafePatternCharacter(entry),
    )
  ) {
    throw new Error("probe_selector_invalid");
  }
  return {
    probeId,
    probeHost,
    probePort,
    ...(include.length > 0 ? { include: include.join(",") } : {}),
    ...(exclude.length > 0 ? { exclude: exclude.join(",") } : {}),
  };
}

function isRegularFile(value: string | undefined): value is string {
  if (!value) return false;
  try {
    return fsSync.statSync(value).isFile();
  } catch {
    return false;
  }
}

function findRepositoryRoot(): string | undefined {
  const candidates = [path.resolve(process.cwd())];
  let current = path.resolve(__dirname);
  for (let index = 0; index < 10; index += 1) {
    candidates.push(current);
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  return candidates.find(
    (candidate) =>
      isRegularFile(path.join(candidate, "package.json")) &&
      isRegularFile(path.join(candidate, "java-agent", "pom.xml")),
  );
}

function findBundledArtifact(input: {
  environmentVariable: string;
  targetDirectory: string;
  jarPattern: RegExp;
}): string | undefined {
  const configured = process.env[input.environmentVariable]?.trim();
  if (configured && isRegularFile(configured)) return path.resolve(configured);
  const root = findRepositoryRoot();
  if (!root) return undefined;
  const targetDirectory = path.join(root, input.targetDirectory);
  try {
    const name = fsSync
      .readdirSync(targetDirectory)
      .filter((entry) => input.jarPattern.test(entry))
      .filter((entry) => !entry.endsWith("-sources.jar") && !entry.endsWith("-javadoc.jar"))
      .sort((left, right) => right.localeCompare(left))[0];
    return name ? path.join(targetDirectory, name) : undefined;
  } catch {
    return undefined;
  }
}

async function bundleLifecycleArtifacts(exportDirAbs: string): Promise<{
  helperJarRel: string;
  agentJarRel: string;
}> {
  const helperJarAbs = findBundledArtifact({
    environmentVariable: HELPER_ARTIFACT_ENV,
    targetDirectory: path.join("java-agent", "core", "core-jvm-attach", "target"),
    jarPattern: /^mcp-java-dev-tools-core-jvm-attach-.+\.jar$/u,
  });
  const agentJarAbs = findBundledArtifact({
    environmentVariable: AGENT_ARTIFACT_ENV,
    targetDirectory: path.join("java-agent", "core", "core-probe", "target"),
    jarPattern: /^mcp-java-dev-tools-agent-.+-all\.jar$/u,
  });
  if (!helperJarAbs || !agentJarAbs) {
    throw new Error("portable_dynamic_attach_artifact_unavailable");
  }
  const sidecarDirAbs = path.join(exportDirAbs, "sidecar");
  await fs.mkdir(sidecarDirAbs, { recursive: true });
  const helperJarRel = path.join("sidecar", "jvm-attach-helper.jar");
  const agentJarRel = path.join("sidecar", "sidecar-agent.jar");
  await Promise.all([
    fs.copyFile(helperJarAbs, path.join(exportDirAbs, helperJarRel)),
    fs.copyFile(agentJarAbs, path.join(exportDirAbs, agentJarRel)),
  ]);
  return {
    helperJarRel: helperJarRel.replace(/\\/g, "/"),
    agentJarRel: agentJarRel.replace(/\\/g, "/"),
  };
}

function buildPs1Sections(): Pick<
  PortableDynamicAttachExport,
  "attachPs1Section" | "cleanupPs1Section"
> {
  return {
    attachPs1Section: [
      "if ($script:McpJvmOwnedRuntimeProcesses.Count -ne 1) { throw 'portable_dynamic_attach_runtime_pid_invalid' }",
      "& node (Join-Path $script:McpJvmExportScriptDir 'run-portable-sidecar-lifecycle.js') '--action' 'attach' '--pid' ([string]$script:McpJvmOwnedRuntimeProcesses[0].Id) '--config' (Join-Path $script:McpJvmExportScriptDir 'portable-sidecar-attach.config.json') '--evidence' (Join-Path $script:McpJvmExportScriptDir 'portable-sidecar.lifecycle.json')",
      "if ($LASTEXITCODE -ne 0) { throw 'portable_dynamic_attach_failed' }",
      "$script:McpJvmPortableDynamicAttachActive = $true",
    ],
    cleanupPs1Section: [
      "if ($script:McpJvmPortableDynamicAttachActive) {",
      "  & node (Join-Path $script:McpJvmExportScriptDir 'run-portable-sidecar-lifecycle.js') '--action' 'deactivate' '--config' (Join-Path $script:McpJvmExportScriptDir 'portable-sidecar-attach.config.json') '--evidence' (Join-Path $script:McpJvmExportScriptDir 'portable-sidecar.lifecycle.json')",
      "  if ($LASTEXITCODE -ne 0) { throw 'cleanup_unverified' }",
      "}",
    ],
  };
}

function buildShSection(): Pick<PortableDynamicAttachExport, "attachShSection"> {
  return {
    attachShSection: [
      'if [ "${#__MCPJVM_OWNED_RUNTIME_PIDS[@]}" -ne 1 ]; then echo "portable_dynamic_attach_runtime_pid_invalid" >&2; exit 1; fi',
      'node "${__MCPJVM_EXPORT_SCRIPT_DIR}/run-portable-sidecar-lifecycle.js" --action attach --pid "${__MCPJVM_OWNED_RUNTIME_PIDS[0]}" --config "${__MCPJVM_EXPORT_SCRIPT_DIR}/portable-sidecar-attach.config.json" --evidence "${__MCPJVM_EXPORT_SCRIPT_DIR}/portable-sidecar.lifecycle.json"',
      "__mcpjvm_dynamic_attach_cleanup() {",
      '  if ! node "${__MCPJVM_EXPORT_SCRIPT_DIR}/run-portable-sidecar-lifecycle.js" --action deactivate --config "${__MCPJVM_EXPORT_SCRIPT_DIR}/portable-sidecar-attach.config.json" --evidence "${__MCPJVM_EXPORT_SCRIPT_DIR}/portable-sidecar.lifecycle.json"; then',
      "    echo 'cleanup_unverified' >&2",
      "    return 1",
      "  fi",
      "}",
    ],
  };
}

export async function preparePerformancePortableDynamicAttach(input: {
  workspaceRootAbs: string;
  exportDirAbs: string;
  workspace: Record<string, unknown> | undefined;
  runtimeContextName?: string;
}): Promise<PortableDynamicAttachExport> {
  const runtimeContext = resolveRuntimeContext({
    workspace: input.workspace,
    ...(input.runtimeContextName ? { runtimeContextName: input.runtimeContextName } : {}),
  });
  if (!runtimeContext || !isRecord(runtimeContext.sidecarLifecycle)) {
    return { attachPs1Section: [], cleanupPs1Section: [], attachShSection: [] };
  }
  const runtimeContextName = asString(runtimeContext.name);
  if (!runtimeContextName) throw new Error("portable_dynamic_attach_runtime_invalid");
  const startup = validateDirectJavaStartup({
    runtimeContext,
    ...(input.runtimeContextName ? { runtimeContextName: input.runtimeContextName } : {}),
  });
  const probe = resolveProbeAttachConfig({
    workspaceRootAbs: input.workspaceRootAbs,
    policy: startup.policy,
  });
  const artifactPaths = await bundleLifecycleArtifacts(input.exportDirAbs);
  const config: ResolvedPortableDynamicAttach & {
    version: 1;
    helperJarRel: string;
    agentJarRel: string;
    statusPath: string;
    resetPath: string;
  } = {
    version: 1,
    runtimeContextName,
    startupName: startup.startupName,
    ...probe,
    ...artifactPaths,
    statusPath: CONFIG_DEFAULTS.PROBE_STATUS_PATH,
    resetPath: CONFIG_DEFAULTS.PROBE_RESET_PATH,
  };
  await fs.writeFile(
    path.join(input.exportDirAbs, CONFIG_FILE_NAME),
    `${JSON.stringify(config, null, 2)}\n`,
    "utf8",
  );
  const runner = await fs.readFile(path.join(__dirname, "templates", "run-portable-sidecar-lifecycle.js.eta"), "utf8");
  await fs.writeFile(path.join(input.exportDirAbs, RUNNER_FILE_NAME), runner, "utf8");
  return {
    ...buildPs1Sections(),
    ...buildShSection(),
  };
}
