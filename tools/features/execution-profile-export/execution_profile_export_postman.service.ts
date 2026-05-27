import { promises as fs } from "node:fs";
import { spawn } from "node:child_process";
import path from "node:path";
import vm from "node:vm";

import { loadExecutionProfileExportManifest } from "@tools-export-execution-profile/loaders/export_manifest.loader";
import { loadPlanContract } from "@tools-export-execution-profile/loaders/plan_contract.loader";
import { loadProjectWorkspace } from "@tools-export-execution-profile/loaders/project_workspace.loader";
import { resolveExportDefaults } from "@tools-export-execution-profile/policy/export_defaults.policy";
import { resolveOneOffExportDir } from "@tools-export-execution-profile/sections/shared/oneoff_export_dir.util";
import { resolvePlanBaseUrls } from "@tools-export-execution-profile/sections/sh/plan_execution.section";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { resolveStepTransport } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";

import type { ExportExecutionProfilePs1Input } from "@tools-export-execution-profile/models/execution_profile_export.model";

export type ExportExecutionProfilePostmanInput = ExportExecutionProfilePs1Input;
export type ExportExecutionProfilePostmanResult = {
  exportId: string;
  exportDirAbs: string;
  collectionPathAbs: string;
  environmentPathAbs: string;
};

type ScriptRef = { name: string; phase?: "preRuntime" | "postRuntime" | "postHealthcheck" | "prePlan" };
type WorkspaceScript = { name: string; command: string; args?: string[]; appdir?: string; envFileArg?: string };
type WorkspaceVariables = { bearerTokenEnv?: string };
type PlanPrerequisite = { key: string; required: boolean; secret: boolean; default?: unknown };
const PROVISIONING_TOKEN_PATTERN = /\b(docker|compose|podman|kubectl|helm|terraform|ansible|vagrant|minikube)\b/i;
const POSTMAN_API_TOKEN_PATTERN = /\b(pm|postman)\s*\./;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const out = value.trim();
  return out.length > 0 ? out : undefined;
}

function normalizePlaceholders(value: string): string {
  return value.replace(/\$\{([^}]+)\}/g, (_full, key: string) => `{{${key.trim()}}}`);
}

function resolvePostmanRuntimeDefault(args: { key: string; value: string; exportId: string }): string {
  const value = args.value.trim();
  if (args.key !== "runId") return args.value;
  if (!/\$\(\s*date\b/i.test(value)) return args.value;
  const ts = args.exportId.slice(0, 15); // YYYYMMDD-HHMMSS
  if (!/^\d{8}-\d{6}$/.test(ts)) return args.exportId.replace(/[^0-9]/g, "");
  return `${ts.slice(0, 8)}${ts.slice(9)}`;
}

function parseDotEnvText(input: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const rawLine of input.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const idx = line.indexOf("=");
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue;
    let value = line.slice(idx + 1);
    if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.slice(1, -1);
    out[key] = value;
  }
  return out;
}

async function loadWorkspaceEnv(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
}): Promise<Record<string, string>> {
  const envFile = typeof input.workspace?.envFile === "string" ? input.workspace.envFile.trim() : "";
  if (!envFile) return {};
  const envFileAbs = path.isAbsolute(envFile) ? envFile : path.resolve(input.workspaceRootAbs, envFile);
  const text = await fs.readFile(envFileAbs, "utf8").catch(() => "");
  return text ? parseDotEnvText(text) : {};
}

function collectPostmanVariables(value: string, out: Set<string>): void {
  const re = /\{\{([^}]+)\}\}/g;
  let match: RegExpExecArray | null = re.exec(value);
  while (match) {
    const key = (match[1] ?? "").trim();
    if (key.length > 0) out.add(key);
    match = re.exec(value);
  }
}

function collectUrlAuthorityVariables(url: string, out: Set<string>): void {
  const authorityVarMatch = url.match(/^\{\{([^}]+)\}\}(?:\/|$)/);
  if (authorityVarMatch && authorityVarMatch[1]) {
    out.add(authorityVarMatch[1].trim());
  }
}

function isSecretLikeKey(key: string): boolean {
  return /(?:^|[._-])(auth|bearer|token|secret|password|credential)(?:$|[._-])/i.test(key);
}

function resolveProfileProvidedContext(workspace: Record<string, unknown> | undefined, executionProfile: string): Map<string, string> {
  const out = new Map<string, string>();
  const profiles = Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles : [];
  const selected = profiles.find((entry): entry is Record<string, unknown> => {
    return isRecord(entry) && asString(entry.executionProfile) === executionProfile;
  });
  const plans = Array.isArray(selected?.plans) ? selected.plans : [];
  for (const plan of plans) {
    if (!isRecord(plan)) continue;
    const providedContext = isRecord(plan.providedContext) ? plan.providedContext : undefined;
    if (!providedContext) continue;
    for (const [key, value] of Object.entries(providedContext)) {
      const normalizedKey = key.trim();
      const normalizedValue = asString(value);
      if (!normalizedKey || !normalizedValue) continue;
      const existing = out.get(normalizedKey);
      if (existing && existing !== normalizedValue) {
        throw new Error(`postman_export_blocked:provided_context_ambiguous:${normalizedKey}`);
      }
      out.set(normalizedKey, normalizedValue);
    }
  }
  return out;
}

function isPostmanRunnableUrl(url: string): boolean {
  if (/^https?:\/\//i.test(url)) return true;
  if (/^\{\{[^}]+\}\}(\/.*)?$/.test(url)) return true;
  return false;
}

function normalizeScriptRefs(workspace: Record<string, unknown> | undefined, executionProfile: string): ScriptRef[] {
  const profiles = Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles : [];
  const selected = profiles.find((entry): entry is Record<string, unknown> => {
    return isRecord(entry) && asString(entry.executionProfile) === executionProfile;
  });
  const refs: ScriptRef[] = [];
  const raw = Array.isArray(selected?.scriptRefs) ? selected.scriptRefs : [];
  for (const item of raw) {
    if (typeof item === "string" && item.trim()) {
      refs.push({ name: item.trim() });
      continue;
    }
    if (!isRecord(item)) continue;
    const name = asString(item.name);
    if (!name) continue;
    const phase = asString(item.phase);
    if (phase === "preRuntime" || phase === "postRuntime" || phase === "postHealthcheck" || phase === "prePlan") {
      refs.push({ name, phase });
    } else {
      refs.push({ name });
    }
  }
  return refs;
}

function normalizeWorkspaceScripts(workspace: Record<string, unknown> | undefined): Map<string, WorkspaceScript> {
  const out = new Map<string, WorkspaceScript>();
  const raw = Array.isArray(workspace?.scripts) ? workspace.scripts : [];
  for (const item of raw) {
    if (!isRecord(item)) continue;
    const name = asString(item.name);
    const command = asString(item.command);
    if (!name || !command) continue;
    const args = Array.isArray(item.args)
      ? item.args
          .filter((arg): arg is string => typeof arg === "string")
          .map((arg) => arg.trim())
          .filter((arg) => arg.length > 0)
      : undefined;
    const appdir = asString(item.appdir);
    const envFileArg = asString(item.envFileArg);
    out.set(name, {
      name,
      command,
      ...(args && args.length > 0 ? { args } : {}),
      ...(appdir ? { appdir } : {}),
      ...(envFileArg ? { envFileArg } : {}),
    });
  }
  return out;
}

function extractScriptPath(script: WorkspaceScript): string | undefined {
  if (!script.args || script.args.length === 0) return undefined;
  const fileFlagIndex = script.args.findIndex((arg) => arg === "-File");
  if (fileFlagIndex >= 0 && fileFlagIndex + 1 < script.args.length) {
    return script.args[fileFlagIndex + 1];
  }
  const direct = script.args.find((arg) => /\.(?:js|mjs|cjs|ts|py|sh|bash|ps1)$/i.test(arg));
  return direct;
}

function resolveWorkspaceEnvFileAbs(args: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
}): string | undefined {
  const envFile = typeof args.workspace?.envFile === "string" ? args.workspace.envFile.trim() : "";
  if (!envFile) return undefined;
  return path.isAbsolute(envFile) ? envFile : path.resolve(args.workspaceRootAbs, envFile);
}

async function executePrerequisiteScripts(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
}): Promise<void> {
  const refs = normalizeScriptRefs(input.workspace, input.executionProfile);
  if (refs.length === 0) return;
  const scripts = normalizeWorkspaceScripts(input.workspace);
  const workspaceEnvFileAbs = resolveWorkspaceEnvFileAbs({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace: input.workspace,
  });
  const envMap = await loadWorkspaceEnv({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace: input.workspace,
  });

  for (const ref of refs) {
    const script = scripts.get(ref.name);
    if (!script) {
      throw new Error(`postman_export_blocked:prerequisite_script_missing:${ref.name}`);
    }
    const joinedCommand = [script.command, ...(script.args ?? [])].join(" ").trim();
    if (PROVISIONING_TOKEN_PATTERN.test(joinedCommand)) {
      throw new Error(`postman_provisioning_not_supported:${ref.name}`);
    }
    const pathOrArg = extractScriptPath(script);
    if (pathOrArg) {
      const ext = path.extname(pathOrArg).toLowerCase();
      if (ext === ".js" || ext === ".mjs" || ext === ".cjs") {
        const scriptAbs = path.isAbsolute(pathOrArg)
          ? pathOrArg
          : path.resolve(input.workspaceRootAbs, pathOrArg);
        const text = await fs.readFile(scriptAbs, "utf8").catch(() => null);
        if (typeof text !== "string") {
          throw new Error(`postman_script_non_convertible:missing_file:${ref.name}`);
        }
        try {
          new vm.Script(text, { filename: scriptAbs });
        } catch {
          throw new Error(`postman_script_invalid_format:${ref.name}`);
        }
        if (!POSTMAN_API_TOKEN_PATTERN.test(text)) {
          throw new Error(`postman_script_invalid_format:${ref.name}:missing_postman_api`);
        }
        continue;
      }
    }
    const renderedArgs = [...(script.args ?? [])];
    if (script.envFileArg && workspaceEnvFileAbs) {
      const envArgIndex = renderedArgs.findIndex((arg) => arg === script.envFileArg);
      if (envArgIndex >= 0) {
        if (envArgIndex + 1 < renderedArgs.length) {
          renderedArgs[envArgIndex + 1] = workspaceEnvFileAbs;
        } else {
          renderedArgs.push(workspaceEnvFileAbs);
        }
      } else {
        renderedArgs.push(script.envFileArg, workspaceEnvFileAbs);
      }
    }
    const cwd = script.appdir
      ? (path.isAbsolute(script.appdir) ? script.appdir : path.resolve(input.workspaceRootAbs, script.appdir))
      : input.workspaceRootAbs;

    await new Promise<void>((resolve, reject) => {
      const child = spawn(script.command, renderedArgs, {
        cwd,
        windowsHide: true,
        env: {
          ...process.env,
          ...envMap,
        },
      });
      let stderr = "";
      child.stderr.on("data", (chunk) => {
        stderr += String(chunk);
      });
      child.on("error", (error) => {
        reject(new Error(`postman_export_blocked:prerequisite_script_failed:${ref.name}:${error.message}`));
      });
      child.on("close", (code) => {
        if (code === 0) {
          resolve();
          return;
        }
        const detail = stderr.trim().replace(/\s+/g, " ").slice(0, 240);
        reject(
          new Error(
            `postman_export_blocked:prerequisite_script_failed:${ref.name}:exit_${String(code ?? "unknown")}${detail ? `:${detail}` : ""}`,
          ),
        );
      });
    });
  }
}

function buildRequestItem(args: {
  planName: string;
  stepOrder: number;
  stepId: string;
  method: string;
  rawUrl: string;
  headers: Record<string, unknown> | undefined;
  body: unknown;
  variables: Set<string>;
  extract?: Array<{ from: string; as: string }>;
}): Record<string, unknown> {
  const normalizedUrl = normalizePlaceholders(args.rawUrl);
  collectPostmanVariables(normalizedUrl, args.variables);
  const header = args.headers
    ? Object.entries(args.headers).map(([key, value]) => ({
        key,
        value: (() => {
          const normalized = normalizePlaceholders(String(value));
          collectPostmanVariables(normalized, args.variables);
          return normalized;
        })(),
      }))
    : [];

  const requestBody =
    typeof args.body === "string"
      ? (() => {
          const normalized = normalizePlaceholders(args.body);
          collectPostmanVariables(normalized, args.variables);
          return { mode: "raw", raw: normalized };
        })()
      : isRecord(args.body)
        ? (() => {
            const normalized = normalizePlaceholders(JSON.stringify(args.body, null, 2));
            collectPostmanVariables(normalized, args.variables);
            return { mode: "raw", raw: normalized, options: { raw: { language: "json" } } };
          })()
        : undefined;

  const event =
    Array.isArray(args.extract) && args.extract.length > 0
      ? [
          {
            listen: "test",
            script: {
              type: "text/javascript",
              exec: [
                "let __body;",
                "try { __body = pm.response.json(); } catch (_e) { __body = undefined; }",
                "const __envelope = { response: { statusCode: pm.response.code, body: __body, headers: pm.response.headers.toObject() } };",
                "const __read = (obj, path) => path.split('.').reduce((acc, segment) => (acc && typeof acc === 'object') ? acc[segment] : undefined, obj);",
                ...args.extract.map((mapping) => {
                  const from = String(mapping.from ?? "").trim();
                  const key = String(mapping.as ?? "").trim();
                  return `(() => { const __v = __read(__envelope, ${JSON.stringify(from)}); if (__v !== undefined && __v !== null && String(__v).trim() !== '') pm.environment.set(${JSON.stringify(key)}, String(__v)); })();`;
                }),
              ],
            },
          },
        ]
      : undefined;

  return {
    name: `${args.planName} #${String(args.stepOrder).padStart(2, "0")} ${args.stepId}`,
    request: {
      method: args.method,
      header,
      ...(requestBody ? { body: requestBody } : {}),
      url: normalizedUrl,
      description: `plan=${args.planName} step=${args.stepId}`,
    },
    ...(event ? { event } : {}),
  };
}

export async function exportExecutionProfilePostman(
  input: ExportExecutionProfilePostmanInput,
): Promise<ExportExecutionProfilePostmanResult> {
  const { manifest, projectRootAbs } = await loadExecutionProfileExportManifest({
    workspaceRootAbs: input.workspaceRootAbs,
    exportId: input.exportId,
  });
  const workspace = await loadProjectWorkspace({
    workspaceRootAbs: input.workspaceRootAbs,
    projectRootAbs,
  });
  const defaults = resolveExportDefaults({
    request: input,
    workspace,
  });

  await executePrerequisiteScripts({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
    executionProfile: manifest.executionProfile,
  });

  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const planBaseUrls = await resolvePlanBaseUrls({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
    executionProfile: manifest.executionProfile,
    planRuns: manifest.planRuns,
  });

  const items: Record<string, unknown>[] = [];
  const referencedVariables = new Set<string>();
  const urlAuthorityVariables = new Set<string>();
  const prerequisiteDefaults = new Map<string, string>();
  const prerequisiteSecretKeys = new Set<string>();
  const requiredPrerequisiteKeys = new Set<string>();
  const allPrerequisites = new Map<string, PlanPrerequisite>();
  const workspaceEnv = await loadWorkspaceEnv({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
  });
  const profileProvidedContext = resolveProfileProvidedContext(workspace, manifest.executionProfile);
  const vars = isRecord(workspace?.variables) ? (workspace.variables as WorkspaceVariables) : {};
  const orderedPlans = [...manifest.planRuns].sort((a, b) => a.order - b.order);
  const firstUseOrderByKey = new Map<string, number>();
  const firstExtractOrderByKey = new Map<string, number>();
  let requestSequence = 0;
  for (const plan of orderedPlans) {
    const contract = await loadPlanContract({
      plansRootAbs,
      planName: plan.planName,
    });
    if (!contract) {
      throw new Error(`postman_export_blocked:plan_contract_unavailable:${plan.planName}`);
    }
    for (const prerequisite of contract.prerequisites) {
      allPrerequisites.set(prerequisite.key, prerequisite);
      if (prerequisite.secret) prerequisiteSecretKeys.add(prerequisite.key);
      if (prerequisite.required) requiredPrerequisiteKeys.add(prerequisite.key);
      if (typeof prerequisite.default === "string" && prerequisite.default.trim().length > 0) {
        prerequisiteDefaults.set(prerequisite.key, prerequisite.default);
      }
    }
    for (const step of [...contract.steps].sort((a, b) => a.order - b.order)) {
      let resolvedHttp: Record<string, unknown> | undefined;
      try {
        const resolved = resolveStepTransport(step, planBaseUrls[plan.planName] ? { apiBaseUrl: planBaseUrls[plan.planName] } : {});
        resolvedHttp = isRecord(resolved.http) ? resolved.http : undefined;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (!message.startsWith("missing_context:")) {
          throw error;
        }
        const fallbackHttp = isRecord(step.transport) && isRecord(step.transport.http) ? step.transport.http : undefined;
        resolvedHttp = fallbackHttp;
      }
      const http = resolvedHttp;
      if (step.protocol !== "http" || !http) {
        throw new Error(`postman_export_blocked:unsupported_transport:${plan.planName}:${step.id}`);
      }
      const method = asString(http.method)?.toUpperCase() ?? "GET";
      const directUrl = asString(http.url);
      const pathTemplate = asString(http.pathTemplate);
      const baseUrl = asString(planBaseUrls[plan.planName]) ?? "{{API_BASE_URL}}";
      const rawUrl =
        directUrl ??
        (pathTemplate
          ? (/^https?:\/\//i.test(pathTemplate)
            ? pathTemplate
            : (/^\{\{[^}]+\}\}(?:\/|$)/.test(normalizePlaceholders(pathTemplate))
              ? pathTemplate
              : `${baseUrl.replace(/\/$/, "")}${pathTemplate.startsWith("/") ? "" : "/"}${pathTemplate}`))
          : undefined);
      if (!rawUrl) {
        throw new Error(`postman_export_blocked:url_unresolved:${plan.planName}:${step.id}`);
      }
      const normalizedUrl = normalizePlaceholders(rawUrl);
      if (!isPostmanRunnableUrl(normalizedUrl)) {
        throw new Error(`postman_export_blocked:url_unrunnable:${plan.planName}:${step.id}`);
      }
      collectUrlAuthorityVariables(normalizedUrl, urlAuthorityVariables);
      const stepVariables = new Set<string>();
      const extractMappings = Array.isArray(step.extract) ? step.extract : [];
      items.push(
        buildRequestItem({
          planName: plan.planName,
          stepOrder: step.order,
          stepId: step.id,
          method,
          rawUrl,
          headers: isRecord(http.headers) ? http.headers : undefined,
          body: http.body,
          variables: stepVariables,
          ...(extractMappings.length > 0 ? { extract: extractMappings } : {}),
        }),
      );
      requestSequence += 1;
      for (const key of stepVariables) {
        referencedVariables.add(key);
        if (!firstUseOrderByKey.has(key)) firstUseOrderByKey.set(key, requestSequence);
      }
      if (extractMappings.length > 0) {
        for (const mapping of extractMappings) {
          if (!mapping || typeof mapping.as !== "string") continue;
          const key = mapping.as.trim();
          if (!key) continue;
          if (!firstExtractOrderByKey.has(key)) firstExtractOrderByKey.set(key, requestSequence);
        }
      }
    }
  }

  const exportDirAbs = resolveOneOffExportDir(projectRootAbs, new Date());
  await fs.mkdir(exportDirAbs, { recursive: true });
  const collectionPathAbs = path.join(exportDirAbs, "collection.postman.json");
  const environmentPathAbs = path.join(exportDirAbs, "environment.postman.json");

  for (const key of urlAuthorityVariables) {
    const known =
      key === "API_BASE_URL"
        ? asString(planBaseUrls[orderedPlans[0]?.planName ?? ""])
        : prerequisiteDefaults.get(key);
    if (!known) {
      throw new Error(`postman_export_blocked:url_variable_default_missing:${key}`);
    }
  }

  const collection = {
    info: {
      name: `execution-profile:${manifest.executionProfile}:${manifest.exportId}`,
      schema: "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    item: items,
  };
  await fs.writeFile(collectionPathAbs, `${JSON.stringify(collection, null, 2)}\n`, "utf8");

  const normalizedBindings = new Map<string, string>();
  if (isRecord(input.contextBindings)) {
    for (const [key, envKey] of Object.entries(input.contextBindings)) {
      const k = key.trim();
      const v = asString(envKey);
      if (k.length > 0 && v) normalizedBindings.set(k, v);
    }
  }
  const normalizedContextValues = new Map<string, string>();
  if (isRecord(input.contextValues)) {
    for (const [key, value] of Object.entries(input.contextValues)) {
      const k = key.trim();
      if (!k) continue;
      normalizedContextValues.set(k, String(value));
    }
  }
  const resolvedVariableValues = new Map<string, string>();
  for (const key of referencedVariables) {
    let value = "";
    if (normalizedContextValues.has(key)) {
      value = normalizedContextValues.get(key) ?? "";
    } else {
      const bindingEnvKey = normalizedBindings.get(key);
      if (bindingEnvKey) {
        const bound = workspaceEnv[bindingEnvKey];
        if (typeof bound !== "string" || bound.trim().length === 0) {
          throw new Error(`postman_export_blocked:binding_env_missing:${key}:${bindingEnvKey}`);
        }
        value = bound;
      } else if (profileProvidedContext.has(key)) {
        value = profileProvidedContext.get(key) ?? "";
      } else if (key === "API_BASE_URL") {
        value = asString(planBaseUrls[orderedPlans[0]?.planName ?? ""]) ?? "";
      } else if (key === "auth.bearer" && defaults.includeResolvedSecrets) {
        value = workspaceEnv[asString(vars.bearerTokenEnv ?? "") ?? ""] ?? prerequisiteDefaults.get(key) ?? "";
      } else {
        value = resolvePostmanRuntimeDefault({
          key,
          value: prerequisiteDefaults.get(key) ?? "",
          exportId: manifest.exportId,
        });
      }
    }
    resolvedVariableValues.set(key, value);
  }
  for (const key of requiredPrerequisiteKeys) {
    if (!referencedVariables.has(key)) continue;
    const value = resolvedVariableValues.get(key) ?? "";
    const firstUseOrder = firstUseOrderByKey.get(key);
    const firstExtractOrder = firstExtractOrderByKey.get(key);
    const canBeDerivedBeforeUse =
      typeof firstUseOrder === "number" &&
      typeof firstExtractOrder === "number" &&
      firstExtractOrder < firstUseOrder;
    if (canBeDerivedBeforeUse) continue;
    if (!value.trim()) {
      throw new Error(`postman_export_blocked:required_prerequisite_unresolved:${key}`);
    }
  }

  const environment = {
    name: `execution-profile:${manifest.executionProfile}`,
    values: [...referencedVariables]
      .sort((a, b) => a.localeCompare(b))
      .map((key) => ({
        key,
        value: resolvedVariableValues.get(key) ?? "",
        type:
          (allPrerequisites.get(key)?.secret === true) || isSecretLikeKey(key)
            ? "secret"
            : "default",
      })),
    _postman_variable_scope: "environment",
    _postman_exported_using: "mcp-java-dev-tools",
  };
  await fs.writeFile(environmentPathAbs, `${JSON.stringify(environment, null, 2)}\n`, "utf8");

  return {
    exportId: manifest.exportId,
    exportDirAbs,
    collectionPathAbs,
    environmentPathAbs,
  };
}
