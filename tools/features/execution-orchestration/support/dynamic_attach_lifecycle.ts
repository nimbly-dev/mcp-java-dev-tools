import { spawn } from "node:child_process";
import { promises as fs } from "node:fs";
import path from "node:path";

import { createJvmLifecycleDomain, isAllowedProbeHost } from "@tools-feature-jvm-lifecycle";
import { createProbeDomain, type ProbeDomainConfig } from "@tools-feature-probe";
import type {
  ProjectRuntimeContext,
  ProjectRuntimeStartupEntry,
  ProjectSidecarLifecyclePolicy,
  ProjectWorkspaceEntry,
} from "@tools-project-artifact-spec/models/project_artifact.model";
import type { RuntimeSuiteRunResult } from "@tools-execution-plan-spec/models/runtime_suite.model";

const LIFECYCLE_FILE_NAME = "dynamic_attach.lifecycle.json";
const PROCESS_DISCOVERY_ATTEMPTS = 30;
const PROCESS_DISCOVERY_DELAY_MS = 200;
const PROCESS_STOP_ATTEMPTS = 10;
const PROCESS_STOP_DELAY_MS = 200;
const MAX_PATTERN_LENGTH = 2_048;

type DynamicAttachLifecycleStatus =
  | "attached"
  | "in_progress"
  | "terminal"
  | "cancelled"
  | "cleanup_unverified";

export type DynamicAttachLifecycleEvidence = {
  version: 1;
  suiteRunId: string;
  runtimeContextName: string;
  startupName: string;
  status: DynamicAttachLifecycleStatus;
  startup?: {
    pid: string;
    processStartEpochMs: number;
  };
  attach: {
    status: "ok" | "blocked";
    reasonCode: string;
    outcome?: string;
  };
  probe: {
    probeId: string;
    verification: "pending" | "ok" | "blocked";
    reasonCode?: string;
  };
  cleanup?: {
    status: "ok" | "partial" | "blocked" | "unverified";
    reasonCode: string;
    nonRestorableClasses: string[];
    processStop: "stopped" | "not_requested" | "blocked" | "unverified";
  };
  updatedAtEpochMs: number;
};

export type DynamicAttachLifecycleSelection = {
  runtimeContext: ProjectRuntimeContext;
  startup: ProjectRuntimeStartupEntry;
  policy: ProjectSidecarLifecyclePolicy;
};

export type DynamicAttachLifecycleResolution =
  | { ok: true; selection?: DynamicAttachLifecycleSelection }
  | { ok: false; reasonCode: string; requiredUserAction: string[] };

type LifecycleFailure = {
  ok: false;
  reasonCode: string;
  requiredUserAction: string[];
};

type LifecycleSuccess = { ok: true };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function lifecycleArtifactPath(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
}): string {
  return path.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "suite-runs",
    args.suiteRunId,
    LIFECYCLE_FILE_NAME,
  );
}

function isSafeSuiteRunId(value: string): boolean {
  return /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(value);
}

function buildFailure(reasonCode: string, requiredUserAction: string[]): LifecycleFailure {
  return { ok: false, reasonCode, requiredUserAction };
}

function resolveContextNames(args: {
  profile: NonNullable<ProjectWorkspaceEntry["executionProfiles"]>[number];
}): string[] {
  const names = new Set<string>();
  if (args.profile.runtimeContextName) names.add(args.profile.runtimeContextName);
  for (const plan of args.profile.plans) {
    if (plan.runtimeContextName) names.add(plan.runtimeContextName);
  }
  return [...names];
}

export function resolveDynamicAttachLifecycle(args: {
  workspace: ProjectWorkspaceEntry;
  profile: NonNullable<ProjectWorkspaceEntry["executionProfiles"]>[number];
}): DynamicAttachLifecycleResolution {
  const contextNames = resolveContextNames({ profile: args.profile });
  if (contextNames.length > 1) {
    const dynamicNames = contextNames.filter((name) =>
      args.workspace.runtimeContexts?.some(
        (context) =>
          context.name === name && context.sidecarLifecycle?.activation === "dynamic_attach_local",
      ),
    );
    if (dynamicNames.length > 0) {
      return buildFailure("dynamic_attach_runtime_context_ambiguous", [
        "Use one runtimeContextName for every plan in a dynamic_attach_local execution profile.",
      ]);
    }
    return { ok: true };
  }

  const contexts = args.workspace.runtimeContexts ?? [];
  const dynamicContexts = contexts.filter(
    (context) => context.sidecarLifecycle?.activation === "dynamic_attach_local",
  );
  if (contextNames.length === 0 && dynamicContexts.length > 1) {
    return buildFailure("dynamic_attach_runtime_context_ambiguous", [
      "Name the dynamic_attach_local runtime context in the execution profile.",
    ]);
  }
  const selectedContext =
    contextNames.length === 1
      ? contexts.find((context) => context.name === contextNames[0])
      : dynamicContexts[0];
  if (!selectedContext?.sidecarLifecycle) return { ok: true };

  const policy = selectedContext.sidecarLifecycle;
  const startups = selectedContext.startups ?? [];
  const startup = startups.find((entry) => entry.name === policy.targetStartupName);
  if (!startup || startups.length !== 1) {
    return buildFailure("sidecar_lifecycle_invalid", [
      "dynamic_attach_local requires exactly one named terminal startup entry.",
    ]);
  }
  return { ok: true, selection: { runtimeContext: selectedContext, startup, policy } };
}

function readLifecycleEvidence(value: unknown): DynamicAttachLifecycleEvidence | null {
  if (!isRecord(value)) return null;
  if (
    value.version !== 1 ||
    typeof value.suiteRunId !== "string" ||
    typeof value.runtimeContextName !== "string" ||
    typeof value.startupName !== "string" ||
    (value.status !== "attached" &&
      value.status !== "in_progress" &&
      value.status !== "terminal" &&
      value.status !== "cancelled" &&
      value.status !== "cleanup_unverified") ||
    !isRecord(value.attach) ||
    (value.attach.status !== "ok" && value.attach.status !== "blocked") ||
    typeof value.attach.reasonCode !== "string" ||
    !isRecord(value.probe) ||
    typeof value.probe.probeId !== "string" ||
    (value.probe.verification !== "pending" &&
      value.probe.verification !== "ok" &&
      value.probe.verification !== "blocked") ||
    typeof value.updatedAtEpochMs !== "number"
  ) {
    return null;
  }
  const startup =
    isRecord(value.startup) &&
    typeof value.startup.pid === "string" &&
    typeof value.startup.processStartEpochMs === "number"
      ? {
          pid: value.startup.pid,
          processStartEpochMs: value.startup.processStartEpochMs,
        }
      : undefined;
  const cleanup: DynamicAttachLifecycleEvidence["cleanup"] =
    isRecord(value.cleanup) &&
    typeof value.cleanup.reasonCode === "string" &&
    Array.isArray(value.cleanup.nonRestorableClasses) &&
    value.cleanup.nonRestorableClasses.every((entry) => typeof entry === "string") &&
    (value.cleanup.status === "ok" ||
      value.cleanup.status === "partial" ||
      value.cleanup.status === "blocked" ||
      value.cleanup.status === "unverified") &&
    (value.cleanup.processStop === "stopped" ||
      value.cleanup.processStop === "not_requested" ||
      value.cleanup.processStop === "blocked" ||
      value.cleanup.processStop === "unverified")
      ? {
          status: value.cleanup.status,
          reasonCode: value.cleanup.reasonCode,
          nonRestorableClasses: value.cleanup.nonRestorableClasses,
          processStop: value.cleanup.processStop,
        }
      : undefined;
  return {
    version: 1,
    suiteRunId: value.suiteRunId,
    runtimeContextName: value.runtimeContextName,
    startupName: value.startupName,
    status: value.status,
    ...(startup ? { startup } : {}),
    attach: {
      status: value.attach.status,
      reasonCode: value.attach.reasonCode,
      ...(typeof value.attach.outcome === "string" ? { outcome: value.attach.outcome } : {}),
    },
    probe: {
      probeId: value.probe.probeId,
      verification: value.probe.verification,
      ...(typeof value.probe.reasonCode === "string" ? { reasonCode: value.probe.reasonCode } : {}),
    },
    ...(cleanup ? { cleanup } : {}),
    updatedAtEpochMs: value.updatedAtEpochMs,
  };
}

async function readPersistedLifecycle(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
}): Promise<DynamicAttachLifecycleEvidence | null> {
  try {
    const raw = await fs.readFile(lifecycleArtifactPath(args), "utf8");
    return readLifecycleEvidence(JSON.parse(raw) as unknown);
  } catch {
    return null;
  }
}

async function writePersistedLifecycle(args: {
  workspaceRootAbs: string;
  projectName: string;
  evidence: DynamicAttachLifecycleEvidence;
}): Promise<void> {
  const fileAbs = lifecycleArtifactPath({
    workspaceRootAbs: args.workspaceRootAbs,
    projectName: args.projectName,
    suiteRunId: args.evidence.suiteRunId,
  });
  await fs.mkdir(path.dirname(fileAbs), { recursive: true });
  await fs.writeFile(fileAbs, `${JSON.stringify(args.evidence, null, 2)}\n`, "utf8");
}

function parseJvms(response: { structuredContent: Record<string, unknown> }): Array<{
  pid: string;
  processStartEpochMs: number | null;
}> {
  const jvms = response.structuredContent.jvms;
  if (!Array.isArray(jvms)) return [];
  return jvms
    .filter(isRecord)
    .filter((entry) => typeof entry.pid === "string")
    .map((entry) => ({
      pid: entry.pid as string,
      processStartEpochMs:
        typeof entry.processStartEpochMs === "number" ? entry.processStartEpochMs : null,
    }));
}

function responseStatus(response: { structuredContent: Record<string, unknown> }): string {
  const status = response.structuredContent.status;
  return typeof status === "string" ? status : "blocked";
}

function isSafePattern(value: string): boolean {
  return (
    value.length > 0 &&
    value.length <= MAX_PATTERN_LENGTH &&
    !value.includes(",") &&
    !value.includes(";") &&
    !value.includes("=")
  );
}

function resolveRegistryAttachConfig(args: {
  policy: ProjectSidecarLifecyclePolicy;
  probeConfig: ProbeDomainConfig;
}):
  | { ok: true; probeHost: string; probePort: number; include?: string; exclude?: string }
  | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  const registry = args.probeConfig.getProbeRegistry?.();
  if (!registry) {
    return buildFailure("probe_registry_unavailable", [
      "Load the active probe-config.json registry before dynamic attach.",
    ]);
  }
  const probe = registry.probesById.get(args.policy.probeId);
  if (!probe) {
    return buildFailure("probe_id_unknown", [
      `Resolve probeId '${args.policy.probeId}' in the active probe registry.`,
    ]);
  }
  let parsed: URL;
  try {
    parsed = new URL(probe.baseUrl);
  } catch {
    return buildFailure("probe_base_url_invalid", [
      `Use an origin-only HTTP baseUrl for probeId '${args.policy.probeId}'.`,
    ]);
  }
  const normalizedBaseUrl = parsed.origin;
  if (
    parsed.protocol !== "http:" ||
    (probe.baseUrl !== normalizedBaseUrl && probe.baseUrl !== `${normalizedBaseUrl}/`) ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== "/" ||
    parsed.search ||
    parsed.hash ||
    !parsed.port
  ) {
    return buildFailure("probe_base_url_invalid", [
      `Use an origin-only HTTP baseUrl with an explicit port for probeId '${args.policy.probeId}'.`,
    ]);
  }
  const probePort = Number(parsed.port);
  const probeHost = parsed.hostname.replace(/^\[|\]$/gu, "");
  if (!probeHost || !Number.isInteger(probePort) || probePort <= 0 || probePort > 65535) {
    return buildFailure("probe_base_url_invalid", [
      `Use a valid explicit HTTP host and port for probeId '${args.policy.probeId}'.`,
    ]);
  }
  if (!isAllowedProbeHost(probeHost)) {
    return buildFailure("probe_host_not_allowed", [
      `Probe host '${probeHost}' is not allowed by the existing local lifecycle host policy.`,
    ]);
  }
  const include = probe.include.map((entry) => entry.trim());
  const exclude = probe.exclude.map((entry) => entry.trim());
  if (
    include.some((entry) => !isSafePattern(entry)) ||
    exclude.some((entry) => !isSafePattern(entry))
  ) {
    return buildFailure("probe_selector_invalid", [
      "Probe include/exclude patterns must be non-empty and must not contain comma, semicolon, or equals characters.",
    ]);
  }
  return {
    ok: true,
    probeHost,
    probePort,
    ...(include.length > 0 ? { include: include.join(",") } : {}),
    ...(exclude.length > 0 ? { exclude: exclude.join(",") } : {}),
  };
}

async function waitForJvm(args: {
  lifecycleDomain: ReturnType<typeof createJvmLifecycleDomain>;
  pid: string;
  signal?: AbortSignal;
}): Promise<{ ok: true; processStartEpochMs: number } | LifecycleFailure> {
  for (let attempt = 0; attempt < PROCESS_DISCOVERY_ATTEMPTS; attempt += 1) {
    if (args.signal?.aborted)
      return buildFailure("execution_cancelled", ["Execution was cancelled before attach."]);
    const response = await args.lifecycleDomain.listJvms();
    if (responseStatus(response) === "ok") {
      const candidate = parseJvms(response).find((entry) => entry.pid === args.pid);
      if (candidate && typeof candidate.processStartEpochMs === "number") {
        return { ok: true, processStartEpochMs: candidate.processStartEpochMs };
      }
    }
    await new Promise((resolve) => setTimeout(resolve, PROCESS_DISCOVERY_DELAY_MS));
  }
  return buildFailure("process_start_fence_unavailable", [
    `The owned startup PID '${args.pid}' did not expose a process-start fence.`,
  ]);
}

function findJvm(
  lifecycleDomain: ReturnType<typeof createJvmLifecycleDomain>,
  pid: string,
): Promise<{ pid: string; processStartEpochMs: number | null } | undefined> {
  return lifecycleDomain
    .listJvms()
    .then((response) => parseJvms(response).find((entry) => entry.pid === pid));
}

async function stopOwnedProcess(args: {
  lifecycleDomain: ReturnType<typeof createJvmLifecycleDomain>;
  pid: string;
  processStartEpochMs: number;
}): Promise<"stopped" | "unverified"> {
  const current = await findJvm(args.lifecycleDomain, args.pid);
  if (!current) return "stopped";
  if (current.processStartEpochMs !== args.processStartEpochMs) return "unverified";
  try {
    process.kill(Number(args.pid), "SIGTERM");
  } catch {
    return "unverified";
  }
  for (let attempt = 0; attempt < PROCESS_STOP_ATTEMPTS; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, PROCESS_STOP_DELAY_MS));
    const remaining = await findJvm(args.lifecycleDomain, args.pid);
    if (!remaining) return "stopped";
    if (remaining.processStartEpochMs !== args.processStartEpochMs) return "unverified";
  }
  return "unverified";
}

function buildSuiteLifecycleContext(
  evidence: DynamicAttachLifecycleEvidence,
): Record<string, unknown> {
  return {
    dynamicAttachLifecycle: {
      version: evidence.version,
      suiteRunId: evidence.suiteRunId,
      runtimeContextName: evidence.runtimeContextName,
      startupName: evidence.startupName,
      status: evidence.status,
      ...(evidence.startup ? { startup: evidence.startup } : {}),
      attach: evidence.attach,
      probe: evidence.probe,
      ...(evidence.cleanup ? { cleanup: evidence.cleanup } : {}),
      updatedAtEpochMs: evidence.updatedAtEpochMs,
    },
  };
}

export function createDynamicAttachLifecycleController(args: {
  workspaceRootAbs: string;
  projectName: string;
  suiteRunId: string;
  selection?: DynamicAttachLifecycleSelection;
  probeConfig: ProbeDomainConfig;
  signal?: AbortSignal;
}) {
  const lifecycleDomain = createJvmLifecycleDomain();
  const probeDomain = createProbeDomain(args.probeConfig);
  let evidence: DynamicAttachLifecycleEvidence | undefined;
  let ownedProcess = false;

  const persist = async (): Promise<void> => {
    if (evidence)
      await writePersistedLifecycle({
        workspaceRootAbs: args.workspaceRootAbs,
        projectName: args.projectName,
        evidence,
      });
  };

  const updateEvidence = async (patch: Partial<DynamicAttachLifecycleEvidence>): Promise<void> => {
    if (!evidence) return;
    evidence = { ...evidence, ...patch, updatedAtEpochMs: Date.now() };
    await persist();
  };

  const cleanup = async (
    reason: "terminal" | "cancelled",
  ): Promise<LifecycleSuccess | LifecycleFailure> => {
    if (!evidence) return { ok: true };
    if (evidence.cleanup) {
      return evidence.cleanup.status === "unverified"
        ? buildFailure("cleanup_unverified", [
            "Inspect the persisted lifecycle evidence before retrying.",
          ])
        : { ok: true };
    }
    let deactivationStatus: NonNullable<DynamicAttachLifecycleEvidence["cleanup"]>["status"] = "ok";
    let cleanupReasonCode = "cleanup_not_required";
    let nonRestorableClasses: string[] = [];
    if (evidence.attach.status === "ok" && evidence.startup) {
      const current = await findJvm(lifecycleDomain, evidence.startup.pid);
      if (!current || current.processStartEpochMs !== evidence.startup.processStartEpochMs) {
        deactivationStatus = "unverified";
        cleanupReasonCode = "cleanup_unverified";
      } else {
        const response = await lifecycleDomain.deactivate({
          pid: evidence.startup.pid,
          expectedProcessStartEpochMs: evidence.startup.processStartEpochMs,
          confirm: true,
        });
        const lifecycle = isRecord(response.structuredContent.lifecycle)
          ? response.structuredContent.lifecycle
          : undefined;
        const outcome =
          lifecycle && typeof lifecycle.outcome === "string" ? lifecycle.outcome : undefined;
        nonRestorableClasses = Array.isArray(response.structuredContent.nonRestorableClasses)
          ? response.structuredContent.nonRestorableClasses.filter(
              (entry): entry is string => typeof entry === "string",
            )
          : [];
        if (
          responseStatus(response) !== "ok" ||
          (outcome !== "deactivated" && outcome !== "partial")
        ) {
          deactivationStatus = "blocked";
          cleanupReasonCode =
            typeof response.structuredContent.reasonCode === "string"
              ? response.structuredContent.reasonCode
              : "deactivation_unverified";
        } else if (outcome === "partial") {
          deactivationStatus = "partial";
          cleanupReasonCode = "deactivation_partial";
        } else {
          cleanupReasonCode = "deactivated";
        }
      }
    }
    let processStop: NonNullable<DynamicAttachLifecycleEvidence["cleanup"]>["processStop"] =
      "not_requested";
    if (ownedProcess && evidence.startup) {
      processStop = await stopOwnedProcess({
        lifecycleDomain,
        pid: evidence.startup.pid,
        processStartEpochMs: evidence.startup.processStartEpochMs,
      });
      if (processStop === "unverified") {
        deactivationStatus = "unverified";
        cleanupReasonCode = "cleanup_unverified";
      }
    }
    const cleanupEvidence = {
      status: deactivationStatus,
      reasonCode: cleanupReasonCode,
      nonRestorableClasses,
      processStop,
    } satisfies NonNullable<DynamicAttachLifecycleEvidence["cleanup"]>;
    await updateEvidence({
      status: deactivationStatus === "unverified" ? "cleanup_unverified" : reason,
      cleanup: cleanupEvidence,
    });
    if (deactivationStatus === "unverified") {
      return buildFailure("cleanup_unverified", [
        "Inspect lifecycle evidence and verify the owned process and Sidecar state before retrying.",
      ]);
    }
    if (deactivationStatus === "blocked") {
      return buildFailure(cleanupReasonCode, [
        "Sidecar deactivation did not return verified evidence.",
      ]);
    }
    return { ok: true };
  };

  return {
    enabled: Boolean(args.selection),
    get evidence(): DynamicAttachLifecycleEvidence | undefined {
      return evidence;
    },
    decorateSuite(suite: RuntimeSuiteRunResult): RuntimeSuiteRunResult {
      if (!evidence) return suite;
      return {
        ...suite,
        suiteContext: {
          ...(isRecord(suite.suiteContext) ? suite.suiteContext : {}),
          ...buildSuiteLifecycleContext(evidence),
        },
      };
    },
    async prepare(): Promise<LifecycleSuccess | LifecycleFailure> {
      if (!args.selection) return { ok: true };
      if (!isSafeSuiteRunId(args.suiteRunId)) {
        return buildFailure("suite_run_id_invalid", [
          "Use a suiteRunId containing only letters, numbers, dot, underscore, and hyphen.",
        ]);
      }
      const persisted = await readPersistedLifecycle({
        workspaceRootAbs: args.workspaceRootAbs,
        projectName: args.projectName,
        suiteRunId: args.suiteRunId,
      });
      if (persisted) {
        evidence = persisted;
        if (persisted.status === "in_progress" || persisted.status === "attached") {
          if (
            !persisted.startup ||
            persisted.attach.status !== "ok" ||
            persisted.probe.verification !== "ok"
          ) {
            return buildFailure("suite_lifecycle_evidence_invalid", [
              "Persisted dynamic attach evidence is incomplete; do not resume this suiteRunId.",
            ]);
          }
          const current = await findJvm(lifecycleDomain, persisted.startup.pid);
          if (!current || current.processStartEpochMs !== persisted.startup.processStartEpochMs) {
            return buildFailure("owned_runtime_missing", [
              `The owned runtime for suiteRunId '${args.suiteRunId}' is no longer fenced to the persisted PID.`,
            ]);
          }
          ownedProcess = true;
          return { ok: true };
        }
        return buildFailure("suite_lifecycle_terminal", [
          `suiteRunId '${args.suiteRunId}' already has terminal lifecycle evidence.`,
        ]);
      }
      const registryConfig = resolveRegistryAttachConfig({
        policy: args.selection.policy,
        probeConfig: args.probeConfig,
      });
      if (!registryConfig.ok) return registryConfig;
      evidence = {
        version: 1,
        suiteRunId: args.suiteRunId,
        runtimeContextName: args.selection.runtimeContext.name,
        startupName: args.selection.startup.name,
        status: "in_progress",
        attach: { status: "blocked", reasonCode: "attach_not_started" },
        probe: { probeId: args.selection.policy.probeId, verification: "pending" },
        updatedAtEpochMs: Date.now(),
      };
      const child = spawn(args.selection.startup.command, args.selection.startup.args ?? [], {
        cwd: args.selection.startup.appdir
          ? path.resolve(args.workspaceRootAbs, args.selection.startup.appdir)
          : args.workspaceRootAbs,
        env: { ...process.env, ...(args.selection.startup.env ?? {}) },
        detached: true,
        stdio: "ignore",
        windowsHide: true,
      });
      child.once("error", () => undefined);
      const pid = child.pid;
      child.unref();
      if (!pid || pid <= 0) {
        await updateEvidence({ attach: { status: "blocked", reasonCode: "runtime_start_failed" } });
        return buildFailure("runtime_start_failed", [
          "The terminal startup did not produce a child PID.",
        ]);
      }
      ownedProcess = true;
      evidence.startup = { pid: String(pid), processStartEpochMs: 0 };
      await persist();
      const fence = await waitForJvm({
        lifecycleDomain,
        pid: String(pid),
        ...(args.signal ? { signal: args.signal } : {}),
      });
      if (!fence.ok) {
        evidence.startup.processStartEpochMs = 0;
        evidence.attach = { status: "blocked", reasonCode: fence.reasonCode };
        await persist();
        const cleanupResult = await cleanup(args.signal?.aborted ? "cancelled" : "terminal");
        if (!cleanupResult.ok) return cleanupResult;
        return fence;
      }
      evidence.startup.processStartEpochMs = fence.processStartEpochMs;
      await persist();
      if (args.signal?.aborted) {
        evidence.attach = { status: "blocked", reasonCode: "execution_cancelled" };
        await persist();
        const cleanupResult = await cleanup("cancelled");
        if (!cleanupResult.ok) return cleanupResult;
        return buildFailure("execution_cancelled", ["Execution was cancelled before attach."]);
      }
      const attachResponse = await lifecycleDomain.attach({
        pid: String(pid),
        expectedProcessStartEpochMs: fence.processStartEpochMs,
        confirm: true,
        probeHost: registryConfig.probeHost,
        probePort: registryConfig.probePort,
        ...(registryConfig.include ? { include: registryConfig.include } : {}),
        ...(registryConfig.exclude ? { exclude: registryConfig.exclude } : {}),
      });
      const lifecycle = isRecord(attachResponse.structuredContent.lifecycle)
        ? attachResponse.structuredContent.lifecycle
        : undefined;
      const outcome =
        lifecycle && typeof lifecycle.outcome === "string" ? lifecycle.outcome : undefined;
      evidence.attach = {
        status: responseStatus(attachResponse) === "ok" && outcome === "active" ? "ok" : "blocked",
        reasonCode:
          typeof attachResponse.structuredContent.reasonCode === "string"
            ? attachResponse.structuredContent.reasonCode
            : "attach_unverified",
        ...(outcome ? { outcome } : {}),
      };
      await persist();
      if (evidence.attach.status !== "ok") {
        const cleanupResult = await cleanup("terminal");
        if (!cleanupResult.ok) return cleanupResult;
        return buildFailure(evidence.attach.reasonCode, [
          "Dynamic Sidecar attach did not return verified active evidence.",
        ]);
      }
      const probeResponse = await probeDomain.check({ probeId: args.selection.policy.probeId });
      evidence.probe = {
        probeId: args.selection.policy.probeId,
        verification: responseStatus(probeResponse) === "ok" ? "ok" : "blocked",
        ...(typeof probeResponse.structuredContent.reasonCode === "string"
          ? { reasonCode: probeResponse.structuredContent.reasonCode }
          : {}),
      };
      await persist();
      if (evidence.probe.verification !== "ok") {
        const cleanupResult = await cleanup("terminal");
        if (!cleanupResult.ok) return cleanupResult;
        return buildFailure(evidence.probe.reasonCode ?? "probe_verification_failed", [
          "Canonical Probe verification failed after dynamic attach.",
        ]);
      }
      evidence.status = "attached";
      await persist();
      return { ok: true };
    },
    async markInProgress(): Promise<void> {
      if (!evidence) return;
      await updateEvidence({ status: "in_progress" });
    },
    async cleanup(reason: "terminal" | "cancelled"): Promise<LifecycleSuccess | LifecycleFailure> {
      return cleanup(reason);
    },
  };
}
