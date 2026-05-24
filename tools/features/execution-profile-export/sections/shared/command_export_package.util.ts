import { promises as fs } from "node:fs";
import path from "node:path";

export type CommandExportScriptPhase = "preRuntime" | "postRuntime" | "postHealthcheck" | "prePlan";

export type CommandExportScriptEntry = {
  name: string;
  command: string;
  args?: string[];
  appdir?: string;
  env?: Record<string, string>;
  envFileArg?: string;
  phase?: CommandExportScriptPhase;
};

export type CommandExportArg =
  | { kind: "literal"; value: string }
  | { kind: "exportPath"; relPath: string }
  | { kind: "projectEnv" };

export type CommandExportBundledScript = {
  script: CommandExportScriptEntry;
  phase: CommandExportScriptPhase;
  args: CommandExportArg[];
};

export type CommandExportPackage = {
  bundledScripts: CommandExportBundledScript[];
};

const SCRIPT_PHASES: CommandExportScriptPhase[] = ["preRuntime", "postRuntime", "postHealthcheck", "prePlan"];
const SCRIPT_FILE_EXTENSIONS = new Set([".ps1", ".sh", ".bash", ".js", ".mjs", ".cjs", ".py"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sanitizePathSegment(value: string, fallback: string): string {
  const sanitized = value
    .trim()
    .replace(/[^A-Za-z0-9._-]/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "");
  return sanitized.length > 0 ? sanitized : fallback;
}

function parseDotEnvText(input: string): Map<string, string> {
  const values = new Map<string, string>();
  for (const rawLine of input.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue;
    let value = line.slice(eq + 1);
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1);
    }
    values.set(key, value);
  }
  return values;
}

function stringifyDotEnvValue(value: string): string {
  if (/^[A-Za-z0-9_./:@-]*$/.test(value)) return value;
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

function isSensitiveEnvKey(key: string): boolean {
  return /(?:AUTH|BEARER|TOKEN|SECRET|PASSWORD|CREDENTIAL|USERNAME)/i.test(key);
}

function collectDeclaredEnvKeys(workspace: Record<string, unknown> | undefined): Set<string> {
  const keys = new Set<string>();
  const vars = isRecord(workspace?.variables) ? workspace.variables : undefined;
  if (vars) {
    for (const candidate of [
      vars.bearerTokenEnv,
      vars.keycloakClientIdEnv,
      vars.keycloakClientSecretEnv,
      vars.keycloakUsernameEnv,
      vars.keycloakPasswordEnv,
    ]) {
      if (typeof candidate === "string" && /^[A-Za-z_][A-Za-z0-9_]*$/.test(candidate.trim())) {
        keys.add(candidate.trim());
      }
    }
  }
  for (const key of [
    "AUTH_BEARER",
    "AUTH_BEARER_TOKEN",
    "AUTH_BEARER_TOKEN_ENV_KEY",
    "KEYCLOAK_BASE_URL",
    "KEYCLOAK_REALM",
    "KEYCLOAK_SCOPE",
    "KEYCLOAK_CLIENT_ID",
    "KEYCLOAK_CLIENT_SECRET",
    "KEYCLOAK_USERNAME",
    "KEYCLOAK_PASSWORD",
  ]) {
    keys.add(key);
  }
  return keys;
}

async function readWorkspaceEnvText(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
}): Promise<string> {
  const envFile = typeof input.workspace?.envFile === "string" ? input.workspace.envFile.trim() : "";
  if (!envFile) return "";
  const envFileAbs = path.isAbsolute(envFile) ? envFile : path.resolve(input.workspaceRootAbs, envFile);
  return await fs.readFile(envFileAbs, "utf8").catch(() => "");
}

async function writeProjectEnv(input: {
  mode: "sh" | "ps1";
  exportDirAbs: string;
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  includeResolvedSecrets: boolean;
}): Promise<void> {
  const sourceText = await readWorkspaceEnvText(input);
  const sourceValues = parseDotEnvText(sourceText);
  for (const key of collectDeclaredEnvKeys(input.workspace)) {
    if (!sourceValues.has(key)) sourceValues.set(key, "");
  }
  const lines = [
    `# Runtime inputs for run-execution-profile.${input.mode}`,
    input.includeResolvedSecrets
      ? "# SENSITIVE EXPORT: includeResolvedSecrets=true."
      : "# Secret-like values are blanked because includeResolvedSecrets=false.",
  ];
  for (const [key, rawValue] of [...sourceValues.entries()].sort((left, right) => left[0].localeCompare(right[0]))) {
    const value = !input.includeResolvedSecrets && isSensitiveEnvKey(key) ? "" : rawValue;
    lines.push(`${key}=${stringifyDotEnvValue(value)}`);
  }
  await fs.writeFile(path.join(input.exportDirAbs, "project.env"), `${lines.join("\n")}\n`, "utf8");
}

function normalizeScripts(workspace: Record<string, unknown> | undefined): Map<string, CommandExportScriptEntry> {
  const scripts = new Map<string, CommandExportScriptEntry>();
  const rawScripts = Array.isArray(workspace?.scripts) ? workspace.scripts : [];
  for (const rawScript of rawScripts) {
    if (!isRecord(rawScript)) continue;
    const name = typeof rawScript.name === "string" ? rawScript.name.trim() : "";
    const command = typeof rawScript.command === "string" ? rawScript.command.trim() : "";
    if (!name || !command) continue;
    const args = Array.isArray(rawScript.args)
      ? rawScript.args.filter((arg): arg is string => typeof arg === "string" && arg.trim().length > 0)
      : undefined;
    const phase =
      typeof rawScript.phase === "string" && SCRIPT_PHASES.includes(rawScript.phase as CommandExportScriptPhase)
        ? (rawScript.phase as CommandExportScriptPhase)
        : undefined;
    scripts.set(name, {
      name,
      command,
      ...(args && args.length > 0 ? { args } : {}),
      ...(typeof rawScript.appdir === "string" && rawScript.appdir.trim() ? { appdir: rawScript.appdir } : {}),
      ...(typeof rawScript.envFileArg === "string" && rawScript.envFileArg.trim()
        ? { envFileArg: rawScript.envFileArg.trim() }
        : {}),
      ...(isRecord(rawScript.env) ? { env: rawScript.env as Record<string, string> } : {}),
      ...(phase ? { phase } : {}),
    });
  }
  return scripts;
}

function resolveProfile(workspace: Record<string, unknown> | undefined, executionProfile: string | undefined): Record<string, unknown> | null {
  const profiles = Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles : [];
  return profiles.find((entry): entry is Record<string, unknown> => {
    return isRecord(entry) && typeof entry.executionProfile === "string" && entry.executionProfile === executionProfile;
  }) ?? null;
}

function normalizeScriptRefs(profile: Record<string, unknown> | null): Array<{ name: string; phase?: CommandExportScriptPhase }> {
  const refs: Array<{ name: string; phase?: CommandExportScriptPhase }> = [];
  const rawRefs = Array.isArray(profile?.scriptRefs) ? profile.scriptRefs : [];
  for (const rawRef of rawRefs) {
    if (typeof rawRef === "string" && rawRef.trim()) {
      refs.push({ name: rawRef.trim() });
      continue;
    }
    if (!isRecord(rawRef) || typeof rawRef.name !== "string" || !rawRef.name.trim()) continue;
    const phase =
      typeof rawRef.phase === "string" && SCRIPT_PHASES.includes(rawRef.phase as CommandExportScriptPhase)
        ? (rawRef.phase as CommandExportScriptPhase)
        : undefined;
    refs.push({ name: rawRef.name.trim(), ...(phase ? { phase } : {}) });
  }
  return refs;
}

function resolvePathArgAbs(workspaceRootAbs: string, arg: string): string | null {
  const trimmed = arg.trim();
  if (!trimmed) return null;
  if (!SCRIPT_FILE_EXTENSIONS.has(path.extname(trimmed).toLowerCase())) return null;
  return path.isAbsolute(trimmed) ? path.normalize(trimmed) : path.resolve(workspaceRootAbs, trimmed);
}

async function copyScriptPath(input: {
  workspaceRootAbs: string;
  exportDirAbs: string;
  scriptName: string;
  sourceArg: string;
}): Promise<CommandExportArg> {
  const sourceAbs = resolvePathArgAbs(input.workspaceRootAbs, input.sourceArg);
  if (!sourceAbs) return { kind: "literal", value: input.sourceArg };
  await fs.access(sourceAbs);
  const scriptDirName = sanitizePathSegment(input.scriptName, "script");
  const fileName = sanitizePathSegment(path.basename(sourceAbs), "script");
  const destRel = path.posix.join("scripts", scriptDirName, fileName);
  const destAbs = path.join(input.exportDirAbs, ...destRel.split("/"));
  await fs.mkdir(path.dirname(destAbs), { recursive: true });
  await fs.copyFile(sourceAbs, destAbs);
  return { kind: "exportPath", relPath: destRel };
}

async function renderBundledArgs(input: {
  workspaceRootAbs: string;
  exportDirAbs: string;
  script: CommandExportScriptEntry;
}): Promise<CommandExportArg[]> {
  const args = [...(input.script.args ?? [])];
  const rendered = args.map((value): CommandExportArg => ({ kind: "literal", value }));
  const fileArgIndex = args.findIndex((arg) => arg === "-File");
  if (fileArgIndex >= 0 && fileArgIndex + 1 < args.length) {
    rendered[fileArgIndex + 1] = await copyScriptPath({
      workspaceRootAbs: input.workspaceRootAbs,
      exportDirAbs: input.exportDirAbs,
      scriptName: input.script.name,
      sourceArg: args[fileArgIndex + 1] ?? "",
    });
  } else {
    for (let i = 0; i < args.length; i += 1) {
      rendered[i] = await copyScriptPath({
        workspaceRootAbs: input.workspaceRootAbs,
        exportDirAbs: input.exportDirAbs,
        scriptName: input.script.name,
        sourceArg: args[i] ?? "",
      });
    }
  }
  if (input.script.envFileArg) {
    const envFileArgIndex = args.findIndex((arg) => arg === input.script.envFileArg);
    if (envFileArgIndex >= 0) {
      if (envFileArgIndex + 1 < rendered.length) {
        rendered[envFileArgIndex + 1] = { kind: "projectEnv" };
      } else {
        rendered.push({ kind: "projectEnv" });
      }
    } else {
      rendered.push({ kind: "literal", value: input.script.envFileArg });
      rendered.push({ kind: "projectEnv" });
    }
  }
  return rendered;
}

async function bundleProfileScripts(input: {
  workspaceRootAbs: string;
  exportDirAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string | undefined;
}): Promise<CommandExportBundledScript[]> {
  const refs = normalizeScriptRefs(resolveProfile(input.workspace, input.executionProfile));
  const scripts = normalizeScripts(input.workspace);
  const bundled: CommandExportBundledScript[] = [];
  for (const ref of refs) {
    const script = scripts.get(ref.name);
    if (!script) continue;
    bundled.push({
      script,
      phase: ref.phase ?? script.phase ?? "prePlan",
      args: await renderBundledArgs({
        workspaceRootAbs: input.workspaceRootAbs,
        exportDirAbs: input.exportDirAbs,
        script,
      }),
    });
  }
  return bundled;
}

export async function prepareCommandExportPackage(input: {
  mode: "sh" | "ps1";
  workspaceRootAbs: string;
  exportDirAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string | undefined;
  includeResolvedSecrets: boolean;
}): Promise<CommandExportPackage> {
  await writeProjectEnv(input);
  return {
    bundledScripts: await bundleProfileScripts(input),
  };
}
