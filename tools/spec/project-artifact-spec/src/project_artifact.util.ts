import { promises as fs } from "node:fs";
import path from "node:path";

import type {
  ExternalHealthCheck,
  ProjectArtifact,
  ProjectArtifactValidationResult,
  ProjectExternalSystem,
  ProjectRuntimeContext,
  ProjectRuntimeStartupEntry,
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

function isPositivePort(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0 && value <= 65535;
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
          if (!isRecord(entry)) {
            errors.push(`workspaces[].runtimeContexts[${index}].startups[${startupIndex}] must be object`);
            return null;
          }
          const startupName = asTrimmedString(entry.name);
          const command = asTrimmedString(entry.command);
          if (!startupName) {
            errors.push(
              `workspaces[].runtimeContexts[${index}].startups[${startupIndex}].name is required`,
            );
          }
          if (!command) {
            errors.push(
              `workspaces[].runtimeContexts[${index}].startups[${startupIndex}].command is required`,
            );
          }
          const args = Array.isArray(entry.args)
            ? entry.args
                .filter((arg) => typeof arg === "string")
                .map((arg) => String(arg).trim())
                .filter((arg) => arg.length > 0)
            : undefined;
          const appdir = asTrimmedString(entry.appdir) ?? undefined;
          const env = isRecord(entry.env)
            ? Object.fromEntries(
                Object.entries(entry.env)
                  .filter((row) => typeof row[0] === "string" && typeof row[1] === "string")
                  .map((row) => [String(row[0] ?? "").trim(), String(row[1] ?? "").trim()])
                  .filter((row) => {
                    const key = row[0] ?? "";
                    const value = row[1] ?? "";
                    return key.length > 0 && value.length > 0;
                  }),
              )
            : undefined;
          if (!startupName || !command) return null;
          return {
            name: startupName,
            command,
            ...(args && args.length > 0 ? { args } : {}),
            ...(appdir ? { appdir } : {}),
            ...(env && Object.keys(env).length > 0 ? { env } : {}),
          } satisfies ProjectRuntimeStartupEntry;
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
  if ("auth" in input) {
    errors.push(`workspaces[${index}].auth is unsupported; use variables`);
  }
  let variables: ProjectWorkspaceEntry["variables"] | undefined;
  if (isRecord(input.variables)) {
    const bearerTokenEnv = asTrimmedString(input.variables.bearerTokenEnv) ?? undefined;
    if (bearerTokenEnv && !/^[A-Z_][A-Z0-9_]*$/.test(bearerTokenEnv)) {
      errors.push(`workspaces[${index}].variables.bearerTokenEnv must be ENV_KEY format`);
    }
    if ("bearerToken" in input.variables) {
      errors.push(`workspaces[${index}].variables.bearerToken is forbidden; use bearerTokenEnv`);
    }
    variables = bearerTokenEnv ? { bearerTokenEnv } : undefined;
  }

  const runtimeContexts = Array.isArray(input.runtimeContexts)
    ? input.runtimeContexts
        .map((entry, i) => normalizeRuntimeContext(entry, i, errors))
        .filter((entry): entry is ProjectRuntimeContext => entry !== null)
    : [];
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

  return {
    projectRoot,
    ...(envFile ? { envFile } : {}),
    ...(variables ? { variables } : {}),
    ...(runtimeContexts.length > 0 ? { runtimeContexts } : {}),
    ...(externalSystems.length > 0 ? { externalSystems } : {}),
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
          : errors.some((e) => e.includes("externalSystems"))
            ? "external_system_invalid"
            : "project_artifact_invalid";
    return { ok: false, reasonCode, errors };
  }
  return { ok: true, artifact: { workspaces } };
}

export async function readProjectArtifact(projectsFileAbs: string): Promise<ProjectArtifactValidationResult> {
  const text = await fs.readFile(projectsFileAbs, "utf8");
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
  return validateProjectArtifact(parsed);
}

export async function writeProjectArtifact(projectsFileAbs: string, artifact: ProjectArtifact): Promise<void> {
  await fs.mkdir(path.dirname(projectsFileAbs), { recursive: true });
  await fs.writeFile(projectsFileAbs, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");
}

