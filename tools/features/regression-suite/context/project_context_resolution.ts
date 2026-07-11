/**
 * Regression project-context support owner.
 *
 * This module preserves the existing environment, Probe configuration,
 * runtime-script, health-check, process-control, auto-start, and context
 * selection behavior behind one Feature-local implementation boundary.
 */
import { spawn } from "node:child_process";
import path from "node:path";
import { URL } from "node:url";

import type {
  ProjectRuntimeContext,
  ProjectWorkspaceEntry,
} from "@tools-project-artifact-spec/models/project_artifact.model";
import { readProjectArtifact } from "@tools-feature-artifact-management";
import type {
  ProjectContextBlockedReason,
  ProjectContextResolutionResult,
  ResolveProjectContextArgs,
  RuntimeStartResult,
} from "../models/regression_context.model";
export type {
  ProjectContextResolutionResult,
  ResolveProjectContextArgs,
} from "../models/regression_context.model";

function extractProbePort(baseUrl: string): number | null {
  try {
    const parsed = new URL(baseUrl);
    const port = Number(parsed.port);
    if (!Number.isInteger(port) || port <= 0) return null;
    return port;
  } catch {
    return null;
  }
}

import {
  executeRunPrerequisites,
  executeSharedScriptsForPhase,
  resolveProfileScripts,
  runRequiredHealthChecksWithDedupe,
  selectWorkspace,
  waitForRequiredHealthChecksAfterAutoStart,
} from "./project_runtime_support";
import {
  buildProbeJavaAgentArg,
  extractServerPortFromStartupArgs,
  findPidListeningOnPortWindows,
  httpCheck,
  isPortOpen,
  killProcessByPidWindows,
  readProbeRegistryFromWorkspace,
  readWorkspaceEnvFile,
  resolveProbeBaseUrlFromRegistry,
  resolveWorkspaceRequestTimeoutMs,
  resolveWorkspaceRetryMax,
} from "./project_probe_process_support";
async function defaultRuntimeStarter(args: {
  runtimeContext: ProjectRuntimeContext;
  workspaceRootAbs: string;
}): Promise<RuntimeStartResult> {
  const { runtimeContext, workspaceRootAbs } = args;
  if (runtimeContext.mode === "terminal") {
    const startups = runtimeContext.startups ?? [];
    if (startups.length === 0) {
      return {
        attempted: true,
        success: false,
        detail: "Terminal runtime auto-start requires runtimeContexts[].startups[].",
      };
    }
    const registry = await readProbeRegistryFromWorkspace(workspaceRootAbs);
    if (!registry.ok) {
      return {
        attempted: true,
        success: false,
        detail: registry.detail,
      };
    }
    const started: string[] = [];
    const startedProbeBaseUrls: Array<{ name: string; baseUrl: string }> = [];
    for (const startup of startups) {
      const agent = buildProbeJavaAgentArg({
        serviceName: startup.name,
        profileName: registry.profileName,
        registry: registry.registry,
      });
      if (!agent.ok) {
        return {
          attempted: true,
          success: false,
          detail: agent.detail,
        };
      }
      const existingToolOptions =
        typeof startup.env?.JAVA_TOOL_OPTIONS === "string"
          ? startup.env.JAVA_TOOL_OPTIONS.trim()
          : "";
      const javaToolOptions =
        existingToolOptions.length > 0
          ? `${agent.agentArg} ${existingToolOptions}`
          : agent.agentArg;
      const cwd = startup.appdir
        ? path.isAbsolute(startup.appdir)
          ? startup.appdir
          : path.resolve(workspaceRootAbs, startup.appdir)
        : workspaceRootAbs;
      const apiPort = extractServerPortFromStartupArgs(startup.args);
      if (apiPort) {
        const apiUp = await isPortOpen("127.0.0.1", apiPort);
        const probePort = extractProbePort(agent.probeBaseUrl);
        const probeUp = probePort ? await isPortOpen("127.0.0.1", probePort) : false;
        if (apiUp && !probeUp && process.platform === "win32") {
          const pid = await findPidListeningOnPortWindows(apiPort);
          if (pid) {
            await killProcessByPidWindows(pid);
            await new Promise((resolve) => setTimeout(resolve, 600));
          }
        }
      }
      const isJavaCommand = /(^|\\|\/)java(\.exe)?$/i.test(startup.command.trim());
      const baseArgs = startup.args ?? [];
      const hasJavaAgentArg = baseArgs.some(
        (arg) => typeof arg === "string" && arg.trim().startsWith("-javaagent:"),
      );
      const commandArgs =
        isJavaCommand && !hasJavaAgentArg ? [agent.agentArg, ...baseArgs] : baseArgs;
      try {
        const child = spawn(startup.command, commandArgs, {
          cwd,
          env: {
            ...process.env,
            ...(startup.env ?? {}),
            JAVA_TOOL_OPTIONS: javaToolOptions,
          },
          windowsHide: true,
          detached: true,
          stdio: "ignore",
        });
        child.unref();
        started.push(startup.name);
        startedProbeBaseUrls.push({ name: startup.name, baseUrl: agent.probeBaseUrl });
      } catch (error) {
        return {
          attempted: true,
          success: false,
          detail: `Terminal runtime start failed for '${startup.name}': ${error instanceof Error ? error.message : String(error)}`,
        };
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1200));
    for (const entry of startedProbeBaseUrls) {
      const ok = await httpCheck(`${entry.baseUrl.replace(/\/$/, "")}/__probe/status`, "GET", 2000);
      if (!ok) {
        return {
          attempted: true,
          success: false,
          detail: `Probe listener not reachable after startup for '${entry.name}' at ${entry.baseUrl}.`,
        };
      }
    }
    return {
      attempted: true,
      success: true,
      detail: `Started terminal runtime apps: ${started.join(", ")}`,
    };
  }
  if (runtimeContext.mode !== "docker") {
    return {
      attempted: false,
      success: false,
      detail: `Runtime mode '${runtimeContext.mode}' does not have auto-start command wiring in v1.`,
    };
  }
  if (!runtimeContext.composeFile) {
    return {
      attempted: true,
      success: false,
      detail: "Docker runtime context requires composeFile for auto-start.",
    };
  }
  const composeFileAbs = path.isAbsolute(runtimeContext.composeFile)
    ? runtimeContext.composeFile
    : path.resolve(workspaceRootAbs, runtimeContext.composeFile);
  const command = "docker";
  const cmdArgs = ["compose", "-f", composeFileAbs, "up", "-d"];
  const result = await new Promise<{ code: number; stderr: string }>((resolve) => {
    const child = spawn(command, cmdArgs, { windowsHide: true });
    let stderr = "";
    child.stderr.on("data", (buf) => {
      stderr += String(buf);
    });
    child.on("close", (code) => resolve({ code: typeof code === "number" ? code : 1, stderr }));
    child.on("error", (err) => resolve({ code: 1, stderr: String(err.message ?? err) }));
  });
  if (result.code === 0) {
    return {
      attempted: true,
      success: true,
      detail: `Started via docker compose: ${composeFileAbs}`,
    };
  }
  return {
    attempted: true,
    success: false,
    detail: `docker compose start failed (${composeFileAbs}): ${result.stderr.trim()}`,
  };
}

function selectRuntimeContext(args: {
  runtimeContexts: ProjectRuntimeContext[];
  requestedName?: string;
}): {
  selected?: ProjectRuntimeContext;
  reasonCode?: ProjectContextBlockedReason;
  nextAction?: string;
  requiredUserAction?: string[];
} {
  const { runtimeContexts, requestedName } = args;
  if (runtimeContexts.length === 0) return {};
  if (requestedName) {
    const match = runtimeContexts.find((entry) => entry.name === requestedName);
    if (!match) {
      return {
        reasonCode: "runtime_context_unknown",
        nextAction: `Choose an existing runtime context instead of '${requestedName}'.`,
        requiredUserAction: [`Unknown runtime context '${requestedName}'.`],
      };
    }
    return { selected: match };
  }
  const terminalNamed = runtimeContexts.find(
    (entry) => entry.mode === "terminal" && entry.name === "terminal-cli",
  );
  if (terminalNamed) return { selected: terminalNamed };

  const terminal = runtimeContexts.find((entry) => entry.mode === "terminal");
  if (terminal) return { selected: terminal };

  if (runtimeContexts.length > 1) {
    return {
      reasonCode: "runtime_context_unknown",
      nextAction:
        "Provide runtimeContextName explicitly when multiple non-terminal runtime contexts exist.",
      requiredUserAction: ["Select runtimeContextName explicitly (for example docker-compose)."],
    };
  }

  const only = runtimeContexts[0];
  if (!only) return {};
  return { selected: only };
}

export async function resolveProjectContextForRegression(
  args: ResolveProjectContextArgs,
): Promise<ProjectContextResolutionResult> {
  const parsed = await readProjectArtifact(args.projectsFileAbs).catch(() => ({
    ok: false as const,
    reasonCode: "project_artifact_missing" as const,
    errors: [`Create project artifact at ${args.projectsFileAbs}.`],
  }));
  if (!parsed.ok) {
    return {
      status: "blocked",
      reasonCode: parsed.reasonCode,
      requiredUserAction: parsed.errors,
    };
  }
  const workspace = selectWorkspace(parsed.artifact.workspaces, args.workspaceRootAbs);
  if (!workspace) {
    return {
      status: "blocked",
      reasonCode: "workspace_root_invalid",
      checks: [],
      nextAction: `Add workspace projectRoot '${args.workspaceRootAbs}' to projects.json.`,
      requiredUserAction: [
        `Add workspace projectRoot '${args.workspaceRootAbs}' to projects.json.`,
      ],
    };
  }

  const effectiveDefaults = workspace.defaults
    ? {
        ...workspace.defaults,
        ...(typeof args.defaultsOverride?.requestTimeoutMs === "number"
          ? { requestTimeoutMs: args.defaultsOverride.requestTimeoutMs }
          : {}),
        ...(typeof args.defaultsOverride?.retryMax === "number"
          ? { retryMax: args.defaultsOverride.retryMax }
          : {}),
      }
    : undefined;
  const effectiveWorkspace: ProjectWorkspaceEntry = {
    ...workspace,
    ...(effectiveDefaults ? { defaults: effectiveDefaults } : {}),
  };
  const profileScripts = resolveProfileScripts({
    workspace: effectiveWorkspace,
    ...(args.executionProfileName ? { executionProfileName: args.executionProfileName } : {}),
  });
  let profileScriptChecks: string[] = [];
  let effectiveEnv: Record<string, string | undefined> = {
    ...process.env,
    ...(await readWorkspaceEnvFile({
      workspace: effectiveWorkspace,
      workspaceRootAbs: args.workspaceRootAbs,
    })),
    ...(args.env ?? {}),
  };

  const runtimeContexts = effectiveWorkspace.runtimeContexts ?? [];
  let selectedRuntimeContextName: string | undefined;
  let selectedRuntimeContext: ProjectRuntimeContext | undefined;
  if (runtimeContexts.length > 0) {
    const runtimeSelection = selectRuntimeContext({
      runtimeContexts,
      ...(args.runtimeContextName ? { requestedName: args.runtimeContextName } : {}),
    });
    if (runtimeSelection.reasonCode) {
      const blocked: ProjectContextResolutionResult = {
        status: "blocked",
        reasonCode: runtimeSelection.reasonCode,
        checks: [],
        requiredUserAction: runtimeSelection.requiredUserAction ?? ["Unknown runtime context."],
      };
      if (runtimeSelection.nextAction) blocked.nextAction = runtimeSelection.nextAction;
      return {
        ...blocked,
      };
    }
    selectedRuntimeContext = runtimeSelection.selected;
    selectedRuntimeContextName = runtimeSelection.selected?.name;
  }

  const contextPatch: Record<string, unknown> = {
    "runtime.requestTimeoutMs": resolveWorkspaceRequestTimeoutMs(effectiveWorkspace, 20_000),
    "runtime.retryMax": resolveWorkspaceRetryMax(effectiveWorkspace, 1),
    "runtime.orchestrator.resumePollMax": effectiveWorkspace.defaults?.orchestrator?.resumePollMax,
    "runtime.orchestrator.resumePollIntervalMs":
      effectiveWorkspace.defaults?.orchestrator?.resumePollIntervalMs,
    "runtime.orchestrator.resumePollTimeoutMs":
      effectiveWorkspace.defaults?.orchestrator?.resumePollTimeoutMs,
  };
  const secretContextKeys = new Set<string>();

  if (selectedRuntimeContext) {
    contextPatch["runtime.context.name"] = selectedRuntimeContext.name;
    contextPatch["runtime.context.mode"] = selectedRuntimeContext.mode;
    const autoStart =
      typeof selectedRuntimeContext.autoStart === "boolean"
        ? selectedRuntimeContext.autoStart
        : true;
    const autoStopOnFinish =
      typeof selectedRuntimeContext.autoStopOnFinish === "boolean"
        ? selectedRuntimeContext.autoStopOnFinish
        : true;
    contextPatch["runtime.autoStart"] = autoStart;
    contextPatch["runtime.autoStopOnFinish"] = autoStopOnFinish;
  }

  const preRuntimeScripts = await executeSharedScriptsForPhase({
    scripts: profileScripts,
    phase: "preRuntime",
    workspace: effectiveWorkspace,
    workspaceRootAbs: args.workspaceRootAbs,
    env: effectiveEnv,
  });
  if (preRuntimeScripts.status === "blocked") {
    return {
      status: "blocked",
      reasonCode: preRuntimeScripts.reasonCode,
      checks: preRuntimeScripts.checks,
      nextAction: preRuntimeScripts.nextAction,
      requiredUserAction: preRuntimeScripts.requiredUserAction,
    };
  }
  effectiveEnv = preRuntimeScripts.env;
  profileScriptChecks = [...profileScriptChecks, ...preRuntimeScripts.checks];

  if (args.healthChecksEnabled !== false) {
    const prereqResult = await executeRunPrerequisites({
      workspace: effectiveWorkspace,
      workspaceRootAbs: args.workspaceRootAbs,
      env: effectiveEnv,
      contextPatch,
    });
    if (prereqResult.status === "blocked") {
      return {
        status: "blocked",
        reasonCode: prereqResult.reasonCode,
        checks: [...profileScriptChecks, ...prereqResult.checks],
        nextAction: prereqResult.nextAction,
        requiredUserAction: prereqResult.requiredUserAction,
      };
    }
    const prereqChecks = prereqResult.checks;
    let health = await runRequiredHealthChecksWithDedupe({
      workspace: effectiveWorkspace,
      skipKeys: prereqResult.dedupeKeys,
    });
    let autoStartDetail: string | undefined;
    let autoStartAttempted = false;
    let autoStarted = false;
    const autoStartEnabled = selectedRuntimeContext
      ? typeof selectedRuntimeContext.autoStart === "boolean"
        ? selectedRuntimeContext.autoStart
        : true
      : false;
    if (!health.ok && selectedRuntimeContext && autoStartEnabled) {
      const starter = args.runtimeStarter ?? defaultRuntimeStarter;
      const startResult = await starter({
        runtimeContext: selectedRuntimeContext,
        workspaceRootAbs: workspace.projectRoot,
      });
      autoStartAttempted = startResult.attempted;
      autoStarted = startResult.success;
      autoStartDetail = startResult.detail;
      if (autoStarted) {
        health = await waitForRequiredHealthChecksAfterAutoStart({
          workspace: effectiveWorkspace,
          skipKeys: prereqResult.dedupeKeys,
          runtimeMode: selectedRuntimeContext.mode,
        });
      }
    }
    const hasPostRuntimeScripts = profileScripts.some((entry) => entry.phase === "postRuntime");
    if (health.ok && hasPostRuntimeScripts) {
      const postRuntimeScripts = await executeSharedScriptsForPhase({
        scripts: profileScripts,
        phase: "postRuntime",
        workspace: effectiveWorkspace,
        workspaceRootAbs: args.workspaceRootAbs,
        env: effectiveEnv,
      });
      if (postRuntimeScripts.status === "blocked") {
        return {
          status: "blocked",
          reasonCode: postRuntimeScripts.reasonCode,
          checks: [
            ...profileScriptChecks,
            ...prereqChecks,
            ...health.checks,
            ...postRuntimeScripts.checks,
          ],
          nextAction: postRuntimeScripts.nextAction,
          requiredUserAction: postRuntimeScripts.requiredUserAction,
        };
      }
      effectiveEnv = postRuntimeScripts.env;
      profileScriptChecks = [...profileScriptChecks, ...postRuntimeScripts.checks];
      health = await runRequiredHealthChecksWithDedupe({
        workspace: effectiveWorkspace,
        skipKeys: prereqResult.dedupeKeys,
      });
    }
    let strictProbeBases = Array.isArray(args.strictProbeBaseUrls)
      ? args.strictProbeBaseUrls
          .filter((entry): entry is string => typeof entry === "string")
          .map((entry) => entry.trim())
          .filter((entry) => entry.length > 0)
      : [];
    if (
      strictProbeBases.length === 0 &&
      args.strictProbeVerification === true &&
      selectedRuntimeContext?.mode === "terminal" &&
      Array.isArray(selectedRuntimeContext.startups) &&
      selectedRuntimeContext.startups.length > 0
    ) {
      const registry = await readProbeRegistryFromWorkspace(workspace.projectRoot);
      if (registry.ok) {
        const derived = selectedRuntimeContext.startups
          .map((startup) =>
            resolveProbeBaseUrlFromRegistry({
              registry: registry.registry,
              profileName: registry.profileName,
              probeId: startup.name,
            }),
          )
          .filter((entry): entry is string => typeof entry === "string" && entry.length > 0);
        strictProbeBases = [...new Set(derived)];
      }
    }
    if (health.ok && strictProbeBases.length > 0) {
      const timeoutMs = resolveWorkspaceRequestTimeoutMs(effectiveWorkspace, 3000);
      let unreachableBases: string[] = [];
      for (const probeBase of strictProbeBases) {
        const reachable = await httpCheck(
          `${probeBase.replace(/\/$/, "")}/__probe/status`,
          "GET",
          timeoutMs,
        );
        if (!reachable) unreachableBases.push(probeBase);
      }
      if (unreachableBases.length > 0 && selectedRuntimeContext && autoStartEnabled) {
        const starter = args.runtimeStarter ?? defaultRuntimeStarter;
        const startResult = await starter({
          runtimeContext: selectedRuntimeContext,
          workspaceRootAbs: workspace.projectRoot,
        });
        autoStartAttempted = autoStartAttempted || startResult.attempted;
        autoStarted = startResult.success;
        autoStartDetail = startResult.detail;
        if (autoStarted) {
          await new Promise((resolve) => setTimeout(resolve, 1500));
        }
        unreachableBases = [];
        for (const probeBase of strictProbeBases) {
          const reachable = await httpCheck(
            `${probeBase.replace(/\/$/, "")}/__probe/status`,
            "GET",
            timeoutMs,
          );
          if (!reachable) unreachableBases.push(probeBase);
        }
      }
      if (unreachableBases.length > 0) {
        const checks = [
          ...profileScriptChecks,
          ...prereqChecks,
          ...strictProbeBases.map(
            (probeBase) =>
              `probe:${probeBase}=${unreachableBases.includes(probeBase) ? "unreachable" : "ready"}`,
          ),
        ];
        if (autoStartAttempted) {
          checks.push(`runtime:auto_start=${autoStarted ? "ok" : "failed"}`);
        }
        if (autoStartDetail) {
          checks.push(`runtime:auto_start_detail=${autoStartDetail}`);
        }
        return {
          status: "blocked",
          reasonCode: "external_healthcheck_failed",
          checks,
          nextAction: "Start/restart runtime with MCP javaagent sidecar wiring and retry.",
          requiredUserAction: unreachableBases.map(
            (probeBase) =>
              `Probe endpoint unreachable at ${probeBase}. Start/restart runtime with javaagent and retry.`,
          ),
        };
      }
    }
    if (!health.ok) {
      const checks = [...profileScriptChecks, ...prereqChecks, ...health.checks];
      if (autoStartAttempted) {
        checks.push(`runtime:auto_start=${autoStarted ? "ok" : "failed"}`);
      }
      if (autoStartDetail) {
        checks.push(`runtime:auto_start_detail=${autoStartDetail}`);
      }
      return {
        status: "blocked",
        reasonCode: "external_healthcheck_failed",
        checks,
        nextAction: health.nextAction,
        requiredUserAction: health.requiredUserAction,
      };
    }
    if (autoStartAttempted) {
      contextPatch["runtime.autoStartAttempted"] = true;
      contextPatch["runtime.autoStarted"] = autoStarted;
      if (autoStartDetail) contextPatch["runtime.autoStartDetail"] = autoStartDetail;
    }
    const postHealthcheckScripts = await executeSharedScriptsForPhase({
      scripts: profileScripts,
      phase: "postHealthcheck",
      workspace: effectiveWorkspace,
      workspaceRootAbs: args.workspaceRootAbs,
      env: effectiveEnv,
    });
    if (postHealthcheckScripts.status === "blocked") {
      return {
        status: "blocked",
        reasonCode: postHealthcheckScripts.reasonCode,
        checks: [
          ...profileScriptChecks,
          ...prereqChecks,
          ...health.checks,
          ...postHealthcheckScripts.checks,
        ],
        nextAction: postHealthcheckScripts.nextAction,
        requiredUserAction: postHealthcheckScripts.requiredUserAction,
      };
    }
    effectiveEnv = postHealthcheckScripts.env;
    profileScriptChecks = [...profileScriptChecks, ...postHealthcheckScripts.checks];
  }

  if (args.healthChecksEnabled === false) {
    for (const phase of ["postRuntime", "postHealthcheck"] as const) {
      const scriptResult = await executeSharedScriptsForPhase({
        scripts: profileScripts,
        phase,
        workspace: effectiveWorkspace,
        workspaceRootAbs: args.workspaceRootAbs,
        env: effectiveEnv,
      });
      if (scriptResult.status === "blocked") {
        return {
          status: "blocked",
          reasonCode: scriptResult.reasonCode,
          checks: [...profileScriptChecks, ...scriptResult.checks],
          nextAction: scriptResult.nextAction,
          requiredUserAction: scriptResult.requiredUserAction,
        };
      }
      effectiveEnv = scriptResult.env;
      profileScriptChecks = [...profileScriptChecks, ...scriptResult.checks];
    }
  }

  const prePlanScripts = await executeSharedScriptsForPhase({
    scripts: profileScripts,
    phase: "prePlan",
    workspace: effectiveWorkspace,
    workspaceRootAbs: args.workspaceRootAbs,
    env: effectiveEnv,
  });
  if (prePlanScripts.status === "blocked") {
    return {
      status: "blocked",
      reasonCode: prePlanScripts.reasonCode,
      checks: [...profileScriptChecks, ...prePlanScripts.checks],
      nextAction: prePlanScripts.nextAction,
      requiredUserAction: prePlanScripts.requiredUserAction,
    };
  }
  effectiveEnv = prePlanScripts.env;
  profileScriptChecks = [...profileScriptChecks, ...prePlanScripts.checks];

  const bearerKey = effectiveWorkspace.variables?.bearerTokenEnv;
  if (bearerKey) {
    const bearer = effectiveEnv[bearerKey];
    if (!bearer || bearer.trim().length === 0) {
      return {
        status: "blocked",
        reasonCode: "env_key_missing",
        missing: [bearerKey],
        checks: profileScriptChecks,
        nextAction: `Set ${bearerKey} in .env or environment and retry.`,
        requiredUserAction: [`Set env key '${bearerKey}' before running regression.`],
      };
    }
    contextPatch["auth.bearer"] = bearer;
    secretContextKeys.add("auth.bearer");
  }

  const contextBindings = effectiveWorkspace.variables?.contextBindings;
  if (contextBindings) {
    const missingEnvKeys: string[] = [];
    for (const [contextKey, envKey] of Object.entries(contextBindings)) {
      const value = effectiveEnv[envKey];
      if (!value || value.trim().length === 0) {
        missingEnvKeys.push(envKey);
        continue;
      }
      contextPatch[contextKey] = value;
      secretContextKeys.add(contextKey);
    }
    if (missingEnvKeys.length > 0) {
      const uniqueMissing = [...new Set(missingEnvKeys)].sort((a, b) => a.localeCompare(b));
      return {
        status: "blocked",
        reasonCode: "env_key_missing",
        missing: uniqueMissing,
        checks: profileScriptChecks,
        nextAction: `Set ${uniqueMissing.join(", ")} in .env or environment and retry.`,
        requiredUserAction: uniqueMissing.map(
          (envKey) => `Set env key '${envKey}' before running regression.`,
        ),
      };
    }
  }

  return {
    status: "ok",
    contextPatch,
    secretContextKeys: [...secretContextKeys].sort((a, b) => a.localeCompare(b)),
    ...(selectedRuntimeContextName ? { runtimeContextName: selectedRuntimeContextName } : {}),
  };
}
