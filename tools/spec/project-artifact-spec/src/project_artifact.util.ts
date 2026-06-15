import { promises as fs } from "node:fs";
import path from "node:path";

import type {
  ExternalHealthCheck,
  ProjectArtifact,
  ProjectArtifactValidationResult,
  ProjectExternalSystem,
  RunPrerequisite,
  ExecutionProfileEntry,
  ExecutionProfilePlanEntry,
  ExecutionProfileSuiteType,
  ExecutionProfileScriptRef,
  ProjectCommandEntry,
  ProjectRuntimeContext,
  ProjectRuntimeStartupEntry,
  ProjectScriptEntry,
  ProjectScriptPhase,
  ProjectWorkspaceEntry,
} from "@tools-project-artifact-spec/models/project_artifact.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asTrimmedString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const out = value.trim();
  return out.length > 0 ? out : null;
}

function isAbsolutePathLike(value: string): boolean {
  const text = value.trim();
  if (text.length === 0) return false;
  if (/^[A-Za-z]:[\\/]/.test(text)) return true;
  if (/^\\\\[^\\]/.test(text)) return true;
  if (/^\//.test(text)) return true;
  if (/^~[\\/]/.test(text)) return true;
  return false;
}

function validateReplayableScriptPath(input: {
  value: string | undefined;
  fieldPath: string;
  errors: string[];
}): void {
  if (!input.value) return;
  if (isAbsolutePathLike(input.value)) {
    input.errors.push(`${input.fieldPath} must be relative/replayable (absolute paths are not allowed)`);
  }
}

async function dirExists(abs: string): Promise<boolean> {
  try {
    const st = await fs.stat(abs);
    return st.isDirectory();
  } catch {
    return false;
  }
}

async function fileExists(abs: string): Promise<boolean> {
  try {
    const st = await fs.stat(abs);
    return st.isFile();
  } catch {
    return false;
  }
}

function isPositivePort(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0 && value <= 65535;
}

function normalizeProjectScriptPhase(value: unknown, fieldPath: string, errors: string[]): ProjectScriptPhase | undefined {
  const phase = asTrimmedString(value);
  if (!phase) return undefined;
  if (phase !== "preRuntime" && phase !== "postRuntime" && phase !== "postHealthcheck" && phase !== "prePlan") {
    errors.push(`${fieldPath} must be preRuntime|postRuntime|postHealthcheck|prePlan`);
    return undefined;
  }
  return phase;
}

function normalizeCommandEntry(input: unknown, fieldPath: string, errors: string[]): ProjectCommandEntry | null {
  if (!isRecord(input)) {
    errors.push(`${fieldPath} must be object`);
    return null;
  }
  const name = asTrimmedString(input.name);
  const command = asTrimmedString(input.command);
  if (!name) {
    errors.push(`${fieldPath}.name is required`);
  }
  if (!command) {
    errors.push(`${fieldPath}.command is required`);
  }
  const args = Array.isArray(input.args)
    ? input.args
        .filter((arg) => typeof arg === "string")
        .map((arg) => String(arg).trim())
        .filter((arg) => arg.length > 0)
    : undefined;
  const appdir = asTrimmedString(input.appdir) ?? undefined;
  validateReplayableScriptPath({
    value: appdir,
    fieldPath: `${fieldPath}.appdir`,
    errors,
  });
  const env = isRecord(input.env)
    ? Object.fromEntries(
        Object.entries(input.env)
          .filter((row) => typeof row[0] === "string" && typeof row[1] === "string")
          .map((row) => [String(row[0] ?? "").trim(), String(row[1] ?? "").trim()])
          .filter((row) => {
            const key = row[0] ?? "";
            const value = row[1] ?? "";
            return key.length > 0 && value.length > 0;
          }),
      )
    : undefined;
  const envFileArg = asTrimmedString(input.envFileArg) ?? undefined;
  if (args && args.length > 0) {
    args.forEach((arg, i) =>
      validateReplayableScriptPath({
        value: arg,
        fieldPath: `${fieldPath}.args[${i}]`,
        errors,
      }),
    );
  }
  if (!name || !command) return null;
  return {
    name,
    command,
    ...(args && args.length > 0 ? { args } : {}),
    ...(appdir ? { appdir } : {}),
    ...(env && Object.keys(env).length > 0 ? { env } : {}),
    ...(envFileArg ? { envFileArg } : {}),
  };
}

function normalizeProjectScript(input: unknown, index: number, errors: string[]): ProjectScriptEntry | null {
  const commandEntry = normalizeCommandEntry(input, `workspaces[].scripts[${index}]`, errors);
  if (!commandEntry || !isRecord(input)) return null;
  const phase = normalizeProjectScriptPhase(input.phase, `workspaces[].scripts[${index}].phase`, errors);
  return {
    ...commandEntry,
    ...(phase ? { phase } : {}),
  };
}

function normalizeRuntimeContext(
  input: unknown,
  index: number,
  errors: string[],
): ProjectRuntimeContext | null {
  if (!isRecord(input)) {
    errors.push(`workspaces[].runtimeContexts[${index}] must be object`);
    return null;
  }
  const name = asTrimmedString(input.name);
  const mode = asTrimmedString(input.mode);
  if (!name) errors.push(`workspaces[].runtimeContexts[${index}].name is required`);
  if (mode !== "terminal" && mode !== "docker") {
    errors.push(`workspaces[].runtimeContexts[${index}].mode must be terminal|docker`);
  }
  const composeFile = asTrimmedString(input.composeFile) ?? undefined;
  if (mode === "docker" && !composeFile) {
    errors.push(`workspaces[].runtimeContexts[${index}].composeFile is required for docker mode`);
  }
  if ("startup" in input) {
    errors.push(`workspaces[].runtimeContexts[${index}].startup is unsupported; use startups[]`);
  }
  const startups: ProjectRuntimeStartupEntry[] = Array.isArray(input.startups)
    ? input.startups
        .map((entry, startupIndex) => {
          return normalizeCommandEntry(
            entry,
            `workspaces[].runtimeContexts[${index}].startups[${startupIndex}]`,
            errors,
          ) as ProjectRuntimeStartupEntry | null;
        })
        .filter((entry): entry is ProjectRuntimeStartupEntry => entry !== null)
    : [];
  if (mode === "terminal") {
    const autoStart = typeof input.autoStart === "boolean" ? input.autoStart : true;
    if (autoStart && startups.length === 0) {
      errors.push(`workspaces[].runtimeContexts[${index}].startups[] is required for terminal autoStart`);
    }
  }
  if (!name || (mode !== "terminal" && mode !== "docker")) return null;
  return {
    name,
    mode,
    ...(composeFile ? { composeFile } : {}),
    ...(typeof input.autoStart === "boolean" ? { autoStart: input.autoStart } : {}),
    ...(typeof input.autoStopOnFinish === "boolean"
      ? { autoStopOnFinish: input.autoStopOnFinish }
      : {}),
    ...(startups.length > 0 ? { startups } : {}),
  };
}

function normalizeExecutionProfileScriptRef(
  input: unknown,
  index: number,
  errors: string[],
): ExecutionProfileScriptRef | null {
  if (typeof input === "string") {
    const name = asTrimmedString(input);
    if (!name) {
      errors.push(`workspaces[].executionProfiles[].scriptRefs[${index}] is required`);
      return null;
    }
    return { name };
  }
  if (!isRecord(input)) {
    errors.push(`workspaces[].executionProfiles[].scriptRefs[${index}] must be string|object`);
    return null;
  }
  const name = asTrimmedString(input.name);
  if (!name) {
    errors.push(`workspaces[].executionProfiles[].scriptRefs[${index}].name is required`);
    return null;
  }
  const phase = normalizeProjectScriptPhase(
    input.phase,
    `workspaces[].executionProfiles[].scriptRefs[${index}].phase`,
    errors,
  );
  return {
    name,
    ...(phase ? { phase } : {}),
  };
}

function normalizeHealthCheck(input: unknown, index: number, errors: string[]): ExternalHealthCheck | null {
  if (!isRecord(input)) {
    errors.push(`externalSystems[].healthChecks[${index}] must be object`);
    return null;
  }
  const id = asTrimmedString(input.id);
  const type = asTrimmedString(input.type);
  if (!id) errors.push(`externalSystems[].healthChecks[${index}].id is required`);
  if (type !== "tcp" && type !== "http") {
    errors.push(`externalSystems[].healthChecks[${index}].type must be tcp|http`);
    return null;
  }
  if (type === "tcp") {
    const target = asTrimmedString(input.target);
    if (!target) {
      errors.push(`externalSystems[].healthChecks[${index}].target is required for tcp`);
      return null;
    }
    return {
      id: id ?? `check-${index}`,
      type,
      target,
      ...(typeof input.timeoutMs === "number" ? { timeoutMs: input.timeoutMs } : {}),
      ...(typeof input.required === "boolean" ? { required: input.required } : {}),
    };
  }
  const url = asTrimmedString(input.url);
  if (!url) {
    errors.push(`externalSystems[].healthChecks[${index}].url is required for http`);
    return null;
  }
  const method = asTrimmedString(input.method) ?? undefined;
  return {
    id: id ?? `check-${index}`,
    type,
    ...(method ? { method: method as "GET" | "POST" | "PUT" | "DELETE" | "PATCH" | "HEAD" | "OPTIONS" } : {}),
    url,
    ...(isRecord(input.expect) && typeof input.expect.status === "number"
      ? { expect: { status: input.expect.status } }
      : {}),
    ...(typeof input.timeoutMs === "number" ? { timeoutMs: input.timeoutMs } : {}),
    ...(typeof input.required === "boolean" ? { required: input.required } : {}),
  };
}

function normalizeExternalSystem(input: unknown, index: number, errors: string[]): ProjectExternalSystem | null {
  if (!isRecord(input)) {
    errors.push(`workspaces[].externalSystems[${index}] must be object`);
    return null;
  }
  const name = asTrimmedString(input.name);
  const kind = asTrimmedString(input.kind);
  const host = asTrimmedString(input.host);
  const port = input.port;
  if (!name) errors.push(`workspaces[].externalSystems[${index}].name is required`);
  if (!kind) errors.push(`workspaces[].externalSystems[${index}].kind is required`);
  if (!host) errors.push(`workspaces[].externalSystems[${index}].host is required`);
  if (!isPositivePort(port)) errors.push(`workspaces[].externalSystems[${index}].port is invalid`);
  const healthChecks = Array.isArray(input.healthChecks)
    ? input.healthChecks
        .map((entry, i) => normalizeHealthCheck(entry, i, errors))
        .filter((entry): entry is ExternalHealthCheck => entry !== null)
    : [];
  if (!name || !kind || !host || !isPositivePort(port)) return null;
  return {
    name,
    kind,
    host,
    port,
    ...(healthChecks.length > 0 ? { healthChecks } : {}),
  };
}

function normalizeRunPrerequisite(input: unknown, index: number, errors: string[]): RunPrerequisite | null {
  if (!isRecord(input)) {
    errors.push(`workspaces[].runPrerequisites[${index}] must be object`);
    return null;
  }
  const order = input.order;
  const id = asTrimmedString(input.id);
  const type = asTrimmedString(input.type);
  const onFail = asTrimmedString(input.onFail);
  if (typeof order !== "number" || !Number.isInteger(order) || order <= 0) {
    errors.push(`workspaces[].runPrerequisites[${index}].order must be a positive integer`);
  }
  if (!id) errors.push(`workspaces[].runPrerequisites[${index}].id is required`);
  if (type !== "assert" && type !== "script") {
    errors.push(`workspaces[].runPrerequisites[${index}].type must be assert|script`);
  }
  if (onFail !== "block" && onFail !== "skip_remaining") {
    errors.push(`workspaces[].runPrerequisites[${index}].onFail must be block|skip_remaining`);
  }
  if (type === "assert") {
    if (!isRecord(input.assert)) {
      errors.push(`workspaces[].runPrerequisites[${index}].assert is required for type=assert`);
      return null;
    }
    const kind = asTrimmedString(input.assert.kind);
    if (
      kind !== "env_exists" &&
      kind !== "context_exists" &&
      kind !== "file_exists" &&
      kind !== "port_reachable" &&
      kind !== "url_reachable" &&
      kind !== "command_available"
    ) {
      errors.push(`workspaces[].runPrerequisites[${index}].assert.kind is invalid`);
      return null;
    }
    if ((kind === "env_exists" || kind === "context_exists") && !asTrimmedString(input.assert.key)) {
      errors.push(`workspaces[].runPrerequisites[${index}].assert.key is required for kind=${kind}`);
      return null;
    }
    if (kind === "file_exists" && !asTrimmedString(input.assert.path)) {
      errors.push(`workspaces[].runPrerequisites[${index}].assert.path is required for kind=file_exists`);
      return null;
    }
    if (kind === "port_reachable") {
      if (!asTrimmedString(input.assert.host) || !isPositivePort(input.assert.port)) {
        errors.push(`workspaces[].runPrerequisites[${index}].assert host/port are required for kind=port_reachable`);
        return null;
      }
    }
    if (kind === "url_reachable" && !asTrimmedString(input.assert.url)) {
      errors.push(`workspaces[].runPrerequisites[${index}].assert.url is required for kind=url_reachable`);
      return null;
    }
    if (kind === "command_available" && !asTrimmedString(input.assert.name)) {
      errors.push(`workspaces[].runPrerequisites[${index}].assert.name is required for kind=command_available`);
      return null;
    }
    return {
      order: Number(order),
      id: id ?? `run-prereq-${index + 1}`,
      type: "assert",
      onFail: (onFail as "block" | "skip_remaining") ?? "block",
      assert: {
        kind,
        ...(asTrimmedString(input.assert.key) ? { key: asTrimmedString(input.assert.key) as string } : {}),
        ...(asTrimmedString(input.assert.path) ? { path: asTrimmedString(input.assert.path) as string } : {}),
        ...(asTrimmedString(input.assert.host) ? { host: asTrimmedString(input.assert.host) as string } : {}),
        ...(isPositivePort(input.assert.port) ? { port: input.assert.port } : {}),
        ...(asTrimmedString(input.assert.url) ? { url: asTrimmedString(input.assert.url) as string } : {}),
        ...(asTrimmedString(input.assert.name) ? { name: asTrimmedString(input.assert.name) as string } : {}),
        ...(typeof input.assert.timeoutMs === "number" ? { timeoutMs: input.assert.timeoutMs } : {}),
      },
    };
  }
  if (!isRecord(input.script)) {
    errors.push(`workspaces[].runPrerequisites[${index}].script is required for type=script`);
    return null;
  }
  const command = asTrimmedString(input.script.command);
  if (command !== "python" && command !== "node" && command !== "sh" && command !== "ps") {
    errors.push(`workspaces[].runPrerequisites[${index}].script.command must be python|node|sh|ps`);
    return null;
  }
  const scriptPath = asTrimmedString(input.script.scriptPath);
  if (!scriptPath) {
    errors.push(`workspaces[].runPrerequisites[${index}].script.scriptPath is required`);
    return null;
  }
  validateReplayableScriptPath({
    value: scriptPath,
    fieldPath: `workspaces[].runPrerequisites[${index}].script.scriptPath`,
    errors,
  });
  const args = Array.isArray(input.script.args)
    ? input.script.args
        .filter((arg) => typeof arg === "string")
        .map((arg) => String(arg).trim())
        .filter((arg) => arg.length > 0)
    : undefined;
  if (args && args.length > 0) {
    args.forEach((arg, i) =>
      validateReplayableScriptPath({
        value: arg,
        fieldPath: `workspaces[].runPrerequisites[${index}].script.args[${i}]`,
        errors,
      }),
    );
  }
  const cwd = asTrimmedString(input.script.cwd) ?? undefined;
  validateReplayableScriptPath({
    value: cwd,
    fieldPath: `workspaces[].runPrerequisites[${index}].script.cwd`,
    errors,
  });
  return {
    order: Number(order),
    id: id ?? `run-prereq-${index + 1}`,
    type: "script",
    onFail: (onFail as "block" | "skip_remaining") ?? "block",
    script: {
      command,
      scriptPath,
      ...(args && args.length > 0 ? { args } : {}),
      ...(cwd ? { cwd } : {}),
      ...(typeof input.script.timeoutMs === "number" ? { timeoutMs: input.script.timeoutMs } : {}),
    },
  };
}

function normalizeExecutionProfilePlan(input: unknown, index: number, errors: string[]): ExecutionProfilePlanEntry | null {
  if (!isRecord(input)) {
    errors.push(`workspaces[].executionProfiles[].plans[${index}] must be object`);
    return null;
  }
  if (typeof input.order !== "number" || !Number.isInteger(input.order) || input.order <= 0) {
    errors.push(`workspaces[].executionProfiles[].plans[${index}].order must be positive integer`);
    return null;
  }
  const planName = asTrimmedString(input.planName);
  if (!planName) {
    errors.push(`workspaces[].executionProfiles[].plans[${index}].planName is required`);
    return null;
  }
  const onFail = asTrimmedString(input.onFail);
  if (onFail && onFail !== "inherit" && onFail !== "stop" && onFail !== "continue") {
    errors.push(`workspaces[].executionProfiles[].plans[${index}].onFail must be inherit|stop|continue`);
    return null;
  }
  const runtimeContextName = asTrimmedString(input.runtimeContextName) ?? undefined;
  return {
    order: input.order,
    planName,
    ...(onFail ? { onFail: onFail as "inherit" | "stop" | "continue" } : {}),
    ...(runtimeContextName ? { runtimeContextName } : {}),
    ...(isRecord(input.providedContext) ? { providedContext: input.providedContext } : {}),
  };
}

function normalizeExecutionProfile(input: unknown, index: number, errors: string[]): ExecutionProfileEntry | null {
  if (!isRecord(input)) {
    errors.push(`workspaces[].executionProfiles[${index}] must be object`);
    return null;
  }
  const executionProfile = asTrimmedString(input.executionProfile);
  if (!executionProfile) {
    errors.push(`workspaces[].executionProfiles[${index}].executionProfile is required`);
    return null;
  }
  const executionPolicy = asTrimmedString(input.executionPolicy);
  if (executionPolicy !== "stop_on_fail" && executionPolicy !== "continue_on_fail") {
    errors.push(`workspaces[].executionProfiles[${index}].executionPolicy must be stop_on_fail|continue_on_fail`);
    return null;
  }
  const suiteTypeRaw = asTrimmedString(input.suiteType) ?? "regression";
  if (suiteTypeRaw !== "regression" && suiteTypeRaw !== "performance") {
    errors.push(`workspaces[].executionProfiles[${index}].suiteType must be regression|performance`);
    return null;
  }
  if (!Array.isArray(input.plans) || input.plans.length === 0) {
    errors.push(`workspaces[].executionProfiles[${index}].plans[] is required`);
    return null;
  }
  const plans = input.plans
    .map((entry, i) => normalizeExecutionProfilePlan(entry, i, errors))
    .filter((entry): entry is ExecutionProfilePlanEntry => entry !== null);
  const orders = plans.map((entry) => entry.order).sort((a, b) => a - b);
  for (let i = 0; i < orders.length; i += 1) {
    if (orders[i] !== i + 1) {
      errors.push(`workspaces[].executionProfiles[${index}].plans[].order must be sequential from 1..N`);
      break;
    }
  }
  const runtimeConfig = isRecord(input.runtimeConfig)
    ? {
        ...(typeof input.runtimeConfig.requestTimeoutMs === "number"
          ? { requestTimeoutMs: input.runtimeConfig.requestTimeoutMs }
          : {}),
        ...(typeof input.runtimeConfig.retryMax === "number"
          ? { retryMax: input.runtimeConfig.retryMax }
          : {}),
      }
    : undefined;
  const runtimeContextName =
    asTrimmedString(input.runtimeContextName) ?? asTrimmedString(input.runtimeContext) ?? undefined;
  const scriptRefs = Array.isArray(input.scriptRefs)
    ? input.scriptRefs
        .map((entry, i) => normalizeExecutionProfileScriptRef(entry, i, errors))
        .filter((entry): entry is ExecutionProfileScriptRef => entry !== null)
    : [];
  return {
    executionProfile,
    suiteType: suiteTypeRaw as ExecutionProfileSuiteType,
    ...(runtimeContextName ? { runtimeContextName } : {}),
    executionPolicy,
    ...(runtimeConfig ? { runtimeConfig } : {}),
    ...(scriptRefs.length > 0 ? { scriptRefs } : {}),
    plans,
  };
}

function normalizeWorkspace(input: unknown, index: number, errors: string[]): ProjectWorkspaceEntry | null {
  if (!isRecord(input)) {
    errors.push(`workspaces[${index}] must be object`);
    return null;
  }
  const projectRoot = asTrimmedString(input.projectRoot);
  if (!projectRoot) {
    errors.push(`workspaces[${index}].projectRoot is required`);
    return null;
  }
  const envFile = asTrimmedString(input.envFile) ?? undefined;
  validateReplayableScriptPath({
    value: envFile,
    fieldPath: `workspaces[${index}].envFile`,
    errors,
  });
  if ("auth" in input) {
    errors.push(`workspaces[${index}].auth is unsupported; use variables`);
  }
  let variables: ProjectWorkspaceEntry["variables"] | undefined;
  if (isRecord(input.variables)) {
    const envFields = [
      "bearerTokenEnv",
      "keycloakClientIdEnv",
      "keycloakClientSecretEnv",
      "keycloakUsernameEnv",
      "keycloakPasswordEnv",
    ] as const;
    const normalizedVariables: NonNullable<ProjectWorkspaceEntry["variables"]> = {};
    for (const field of envFields) {
      const envKey = asTrimmedString(input.variables[field]) ?? undefined;
      if (envKey && !/^[A-Z_][A-Z0-9_]*$/.test(envKey)) {
        errors.push(`workspaces[${index}].variables.${field} must be ENV_KEY format`);
      }
      if (envKey) {
        normalizedVariables[field] = envKey;
      }
    }
    if ("bearerToken" in input.variables) {
      errors.push(`workspaces[${index}].variables.bearerToken is forbidden; use bearerTokenEnv`);
    }
    variables = Object.keys(normalizedVariables).length > 0 ? normalizedVariables : undefined;
  }

  const runtimeContexts = Array.isArray(input.runtimeContexts)
    ? input.runtimeContexts
        .map((entry, i) => normalizeRuntimeContext(entry, i, errors))
        .filter((entry): entry is ProjectRuntimeContext => entry !== null)
    : [];
  const scripts = Array.isArray(input.scripts)
    ? input.scripts
        .map((entry, i) => normalizeProjectScript(entry, i, errors))
        .filter((entry): entry is ProjectScriptEntry => entry !== null)
    : [];
  const executionProfiles = Array.isArray(input.executionProfiles)
    ? input.executionProfiles
        .map((entry, i) => normalizeExecutionProfile(entry, i, errors))
        .filter((entry): entry is ExecutionProfileEntry => entry !== null)
    : [];
  const runPrerequisites = Array.isArray(input.runPrerequisites)
    ? input.runPrerequisites
        .map((entry, i) => normalizeRunPrerequisite(entry, i, errors))
        .filter((entry): entry is RunPrerequisite => entry !== null)
    : [];
  if (runPrerequisites.length > 0) {
    const orders = runPrerequisites.map((entry) => entry.order).sort((a, b) => a - b);
    for (let i = 0; i < orders.length; i += 1) {
      if (orders[i] !== i + 1) {
        errors.push("workspaces[].runPrerequisites[].order must be sequential from 1..N");
        break;
      }
    }
  }
  const runtimeContextNames = new Set(runtimeContexts.map((entry) => entry.name));
  executionProfiles.forEach((profile, i) => {
    if (profile.runtimeContextName && !runtimeContextNames.has(profile.runtimeContextName)) {
      errors.push(
        `workspaces[].executionProfiles[${i}].runtimeContextName must match a workspaces[].runtimeContexts[].name`,
      );
    }
    if (!Array.isArray(profile.plans)) return;
    profile.plans.forEach((plan, j) => {
      if (plan.runtimeContextName && !runtimeContextNames.has(plan.runtimeContextName)) {
        errors.push(
          `workspaces[].executionProfiles[${i}].plans[${j}].runtimeContextName must match a workspaces[].runtimeContexts[].name`,
        );
      }
    });
  });
  const scriptNames = new Set(scripts.map((entry) => entry.name));
  executionProfiles.forEach((profile, i) => {
    profile.scriptRefs?.forEach((scriptRef, j) => {
      if (!scriptNames.has(scriptRef.name)) {
        errors.push(
          `workspaces[].executionProfiles[${i}].scriptRefs[${j}].name must match a workspaces[].scripts[].name`,
        );
      }
    });
  });
  const externalSystems = Array.isArray(input.externalSystems)
    ? input.externalSystems
        .map((entry, i) => normalizeExternalSystem(entry, i, errors))
        .filter((entry): entry is ProjectExternalSystem => entry !== null)
    : [];
  const defaults = isRecord(input.defaults)
    ? {
        ...(typeof input.defaults.requestTimeoutMs === "number"
          ? { requestTimeoutMs: input.defaults.requestTimeoutMs }
          : {}),
        ...(typeof input.defaults.retryMax === "number" ? { retryMax: input.defaults.retryMax } : {}),
      }
    : undefined;
  const sessionExport = isRecord(input.sessionExport)
    ? (() => {
        const normalized = {
          ...(typeof input.sessionExport.includeRuntimeStartup === "boolean"
            ? { includeRuntimeStartup: input.sessionExport.includeRuntimeStartup }
            : {}),
          ...(typeof input.sessionExport.includeHealthcheckGate === "boolean"
            ? { includeHealthcheckGate: input.sessionExport.includeHealthcheckGate }
            : {}),
          ...(typeof input.sessionExport.includeResolvedSecrets === "boolean"
            ? { includeResolvedSecrets: input.sessionExport.includeResolvedSecrets }
            : {}),
        };
        return Object.keys(normalized).length > 0 ? normalized : undefined;
      })()
    : undefined;

  return {
    projectRoot,
    ...(envFile ? { envFile } : {}),
    ...(variables ? { variables } : {}),
    ...(runtimeContexts.length > 0 ? { runtimeContexts } : {}),
    ...(scripts.length > 0 ? { scripts } : {}),
    ...(executionProfiles.length > 0 ? { executionProfiles } : {}),
    ...(runPrerequisites.length > 0 ? { runPrerequisites } : {}),
    ...(externalSystems.length > 0 ? { externalSystems } : {}),
    ...(sessionExport ? { sessionExport } : {}),
    ...(defaults ? { defaults } : {}),
  };
}

export function validateProjectArtifact(input: unknown): ProjectArtifactValidationResult {
  if (!isRecord(input) || !Array.isArray(input.workspaces)) {
    return {
      ok: false,
      reasonCode: "project_artifact_invalid",
      errors: ["workspaces[] is required"],
    };
  }
  const errors: string[] = [];
  const workspaces = input.workspaces
    .map((entry, i) => normalizeWorkspace(entry, i, errors))
    .filter((entry): entry is ProjectWorkspaceEntry => entry !== null);
  if (workspaces.length === 0) {
    errors.push("at least one valid workspaces[] entry is required");
  }
  if (errors.length > 0) {
    const reasonCode = errors.some((e) => e.includes("projectRoot"))
      ? "workspace_root_invalid"
      : errors.some((e) => e.includes("bearerTokenEnv"))
        ? "env_key_missing"
        : errors.some((e) => e.includes("runtimeContexts"))
          ? "runtime_context_unknown"
          : errors.some(
                (e) =>
                  e.includes("executionProfiles") &&
                  (e.includes("runtimeContextName must match") ||
                    e.includes("scriptRefs") ||
                    e.includes("plans[].planName") ||
                    e.includes(".plans[")),
              )
            ? "project_reference_invalid"
          : errors.some((e) => e.includes("externalSystems"))
            ? "external_system_invalid"
            : "project_artifact_invalid";
    return { ok: false, reasonCode, errors };
  }
  return { ok: true, artifact: { workspaces } };
}

export async function validateProjectArtifactReferenceIntegrity(args: {
  projectsFileAbs: string;
  artifact: ProjectArtifact;
}): Promise<ProjectArtifactValidationResult> {
  const errors: string[] = [];
  const checks: Array<{ wi: number; pi: number; pli: number; suiteType: ExecutionProfileSuiteType; planRootAbs: string }> = [];
  const artifactRootAbs = path.dirname(args.projectsFileAbs);
  args.artifact.workspaces.forEach((workspace, wi) => {
    const executionProfiles = Array.isArray(workspace.executionProfiles) ? workspace.executionProfiles : [];
    executionProfiles.forEach((profile, pi) => {
      const plans = Array.isArray(profile.plans) ? profile.plans : [];
      plans.forEach((plan, pli) => {
        const planRootAbs = path.join(artifactRootAbs, "plans", profile.suiteType, plan.planName);
        checks.push({ wi, pi, pli, suiteType: profile.suiteType, planRootAbs });
      });
    });
  });
  for (const check of checks) {
    const hasPlanDir = await dirExists(check.planRootAbs);
    const hasMetadata = await fileExists(path.join(check.planRootAbs, "metadata.json"));
    const hasContract = await fileExists(path.join(check.planRootAbs, "contract.json"));
    if (!hasPlanDir || !hasMetadata || !hasContract) {
      errors.push(
        `workspaces[${check.wi}].executionProfiles[${check.pi}].plans[${check.pli}].planName must match an existing ${check.suiteType} plan artifact`,
      );
    }
  }
  if (errors.length > 0) {
    return {
      ok: false,
      reasonCode: "project_reference_invalid",
      errors,
    };
  }
  return { ok: true, artifact: args.artifact };
}

export async function readProjectArtifact(projectsFileAbs: string): Promise<ProjectArtifactValidationResult> {
  const text = (await fs.readFile(projectsFileAbs, "utf8")).replace(/^\uFEFF/, "");
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return {
      ok: false,
      reasonCode: "project_artifact_invalid",
      errors: ["projects.json is not valid JSON"],
    };
  }
  const validated = validateProjectArtifact(parsed);
  if (!validated.ok) return validated;
  return validateProjectArtifactReferenceIntegrity({
    projectsFileAbs,
    artifact: validated.artifact,
  });
}

export async function writeProjectArtifact(projectsFileAbs: string, artifact: ProjectArtifact): Promise<void> {
  await fs.mkdir(path.dirname(projectsFileAbs), { recursive: true });
  await fs.writeFile(projectsFileAbs, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");
}

