import type {
  BuildPreflightArgs,
  PlanContract,
  PlanMetadata,
  PlanPrerequisite,
  PreflightResult,
  PreflightStatus,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import { buildReplayPreflight } from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";
import { resolveProjectContextForRegression } from "@tools-regression-execution-plan-spec/regression_project_context.util";

export type DiscoveryOutcome =
  | "resolved"
  | "unresolved_empty"
  | "unresolved_ambiguous"
  | "blocked_policy"
  | "blocked_runtime_error"
  | "blocked_source_unsupported"
  | "blocked_timeout"
  | "blocked_mutation";

export type DiscoveryReasonCode =
  | "ok"
  | "discoverable_prerequisite_policy_disabled"
  | "discovery_empty_result"
  | "discovery_ambiguous_result"
  | "discovery_adapter_failure"
  | "discovery_source_unsupported"
  | "discovery_timeout"
  | "discovery_mutation_blocked";

export type DiscoverablePrerequisite = PlanPrerequisite & {
  provisioning: "discoverable";
  discoverySource: "datasource" | "runtime_context";
};

export type DiscoveryAdapterInput = {
  prerequisite: DiscoverablePrerequisite;
  key: string;
  providedContext: Record<string, unknown>;
  contract: PlanContract;
  metadata: PlanMetadata;
};

export type DiscoveryAdapterResult =
  | {
      accessMode: "read" | "write";
      outcome: "resolved";
      value: unknown;
      candidateCount?: number;
      sourceRef?: string;
    }
  | {
      accessMode: "read" | "write";
      outcome: "unresolved_empty";
      candidateCount?: number;
      sourceRef?: string;
    }
  | {
      accessMode: "read" | "write";
      outcome: "unresolved_ambiguous";
      candidateCount: number;
      sourceRef?: string;
    };

export type DiscoveryAdapter = (args: DiscoveryAdapterInput) => Promise<DiscoveryAdapterResult>;

export type DiscoveryRecord = {
  key: string;
  source: "datasource" | "runtime_context";
  outcome: DiscoveryOutcome;
  reasonCode: DiscoveryReasonCode;
  candidateCount?: number;
  sourceRef?: string;
  requiredUserAction: string;
};

export type ResolveDiscoverablePrerequisitesArgs = {
  metadata: PlanMetadata;
  contract: PlanContract;
  providedContext: Record<string, unknown>;
  timeoutMs?: number;
  adapters: {
    datasource?: DiscoveryAdapter;
    runtime_context?: DiscoveryAdapter;
  };
};

export type ResolveDiscoverablePrerequisitesResult = {
  status: "resolved" | "blocked";
  reasonCode: DiscoveryReasonCode;
  discoveredContext: Record<string, unknown>;
  records: DiscoveryRecord[];
  requiredUserAction: string[];
};

export type BuildReplayPreflightWithDiscoveryArgs = BuildPreflightArgs & {
  timeoutMs?: number;
  adapters: {
    datasource?: DiscoveryAdapter;
    runtime_context?: DiscoveryAdapter;
  };
  projectContextOptions?: {
    workspaceRootAbs: string;
    projectsFileAbs: string;
    env?: Record<string, string | undefined>;
    runtimeContextName?: string;
    defaultsOverride?: {
      requestTimeoutMs?: number;
      retryMax?: number;
    };
    healthChecksEnabled?: boolean;
  };
};

async function isProbeBaseReachable(urlRaw: string, timeoutMs: number): Promise<boolean> {
  try {
    const ctrl = new AbortController();
    const handle = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const response = await fetch(urlRaw, { method: "GET", signal: ctrl.signal });
      return response.status >= 100;
    } finally {
      clearTimeout(handle);
    }
  } catch {
    return false;
  }
}

function resolveProbeBaseUrl(args: {
  providedContext: Record<string, unknown>;
  contract: PlanContract;
}): string | null {
  const contextValue = args.providedContext["probeBaseUrl"];
  if (typeof contextValue === "string" && contextValue.trim().length > 0) {
    return contextValue.trim();
  }
  const fromPrerequisite = args.contract.prerequisites.find((entry) => entry.key === "probeBaseUrl");
  if (typeof fromPrerequisite?.default === "string" && fromPrerequisite.default.trim().length > 0) {
    return fromPrerequisite.default.trim();
  }
  return null;
}

export type BuildReplayPreflightWithDiscoveryResult = {
  preflight: PreflightResult;
  resolvedContext: Record<string, unknown>;
  discovery: ResolveDiscoverablePrerequisitesResult | null;
};

function hasNonBlank(value: unknown): boolean {
  return typeof value !== "undefined" && value !== null && String(value).trim() !== "";
}

function requiredUserActionForRecord(record: {
  key: string;
  source: "datasource" | "runtime_context";
  outcome: DiscoveryOutcome;
}): string {
  if (record.outcome === "blocked_policy") {
    return "Set metadata.execution.discoveryPolicy to allow_discoverable_prerequisites.";
  }
  if (record.outcome === "blocked_timeout") {
    return `Retry discovery for ${record.key} (${record.source}) with a larger timeout or narrower selector.`;
  }
  if (record.outcome === "blocked_mutation") {
    return `Discovery adapter for ${record.source} attempted non-read access for ${record.key}; enforce read-only mode.`;
  }
  if (record.outcome === "blocked_source_unsupported") {
    return `Configure discovery adapter for ${record.source} and rerun.`;
  }
  if (record.outcome === "blocked_runtime_error") {
    return `Inspect discovery adapter/runtime logs for ${record.key} and rerun.`;
  }
  if (record.outcome === "unresolved_ambiguous") {
    return `Refine selector for ${record.key} to return exactly one candidate.`;
  }
  if (record.outcome === "unresolved_empty") {
    return `Provide ${record.key} manually or seed datasource/runtime context for discovery.`;
  }
  return `No action required for ${record.key}.`;
}

function reasonCodeForOutcome(outcome: DiscoveryOutcome): DiscoveryReasonCode {
  if (outcome === "blocked_policy") return "discoverable_prerequisite_policy_disabled";
  if (outcome === "unresolved_empty") return "discovery_empty_result";
  if (outcome === "unresolved_ambiguous") return "discovery_ambiguous_result";
  if (outcome === "blocked_runtime_error") return "discovery_adapter_failure";
  if (outcome === "blocked_source_unsupported") return "discovery_source_unsupported";
  if (outcome === "blocked_timeout") return "discovery_timeout";
  if (outcome === "blocked_mutation") return "discovery_mutation_blocked";
  return "ok";
}

function statusForOutcome(outcome: DiscoveryOutcome): PreflightStatus {
  if (outcome === "unresolved_ambiguous") return "blocked_ambiguous";
  if (outcome === "unresolved_empty") return "blocked_invalid";
  if (outcome === "blocked_policy") return "blocked_invalid";
  if (outcome === "blocked_runtime_error") return "blocked_invalid";
  if (outcome === "blocked_source_unsupported") return "blocked_invalid";
  if (outcome === "blocked_timeout") return "blocked_invalid";
  if (outcome === "blocked_mutation") return "blocked_invalid";
  return "ready";
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  let handle: NodeJS.Timeout | null = null;
  try {
    return await Promise.race<T>([
      promise,
      new Promise<T>((_resolve, reject) => {
        handle = setTimeout(() => reject(new Error("discovery_timeout")), timeoutMs);
      }),
    ]);
  } finally {
    if (handle) clearTimeout(handle);
  }
}

function discoverablePrerequisites(args: {
  contract: PlanContract;
  providedContext: Record<string, unknown>;
}): DiscoverablePrerequisite[] {
  return args.contract.prerequisites.filter((prerequisite): prerequisite is DiscoverablePrerequisite => {
    if (!prerequisite.required) return false;
    if (prerequisite.provisioning !== "discoverable") return false;
    if (hasNonBlank(args.providedContext[prerequisite.key])) return false;
    if (typeof prerequisite.default !== "undefined") return false;
    return true;
  });
}

function mergeDiscoveredContext(args: {
  providedContext: Record<string, unknown>;
  discoveredContext: Record<string, unknown>;
}): Record<string, unknown> {
  return {
    ...args.discoveredContext,
    ...args.providedContext,
  };
}

export async function resolveDiscoverablePrerequisites(
  args: ResolveDiscoverablePrerequisitesArgs,
): Promise<ResolveDiscoverablePrerequisitesResult> {
  const pending = discoverablePrerequisites({
    contract: args.contract,
    providedContext: args.providedContext,
  });
  if (pending.length === 0) {
    return {
      status: "resolved",
      reasonCode: "ok",
      discoveredContext: {},
      records: [],
      requiredUserAction: [],
    };
  }

  if (args.metadata.execution.discoveryPolicy !== "allow_discoverable_prerequisites") {
    const records: DiscoveryRecord[] = pending.map((prerequisite) => {
      const record = {
        key: prerequisite.key,
        source: prerequisite.discoverySource,
        outcome: "blocked_policy" as const,
      };
      return {
        ...record,
        reasonCode: reasonCodeForOutcome(record.outcome),
        requiredUserAction: requiredUserActionForRecord(record),
      };
    });
    return {
      status: "blocked",
      reasonCode: "discoverable_prerequisite_policy_disabled",
      discoveredContext: {},
      records,
      requiredUserAction: [...new Set(records.map((entry) => entry.requiredUserAction))],
    };
  }

  const timeoutMs = args.timeoutMs ?? 2000;
  const discoveredContext: Record<string, unknown> = {};
  const records: DiscoveryRecord[] = [];

  for (const prerequisite of pending) {
    const adapter = args.adapters[prerequisite.discoverySource];
    if (!adapter) {
      const outcome: DiscoveryOutcome = "blocked_source_unsupported";
      records.push({
        key: prerequisite.key,
        source: prerequisite.discoverySource,
        outcome,
        reasonCode: reasonCodeForOutcome(outcome),
        requiredUserAction: requiredUserActionForRecord({
          key: prerequisite.key,
          source: prerequisite.discoverySource,
          outcome,
        }),
      });
      continue;
    }

    let adapterResult: DiscoveryAdapterResult | null = null;
    let outcome: DiscoveryOutcome = "resolved";
    let reasonCode: DiscoveryReasonCode = "ok";
    let sourceRef: string | undefined;
    let candidateCount: number | undefined;

    try {
      adapterResult = await withTimeout(
        adapter({
          prerequisite,
          key: prerequisite.key,
          providedContext: args.providedContext,
          contract: args.contract,
          metadata: args.metadata,
        }),
        timeoutMs,
      );
    } catch (error) {
      const timeoutError = error instanceof Error && error.message === "discovery_timeout";
      outcome = timeoutError ? "blocked_timeout" : "blocked_runtime_error";
      reasonCode = reasonCodeForOutcome(outcome);
    }

    if (adapterResult) {
      sourceRef = adapterResult.sourceRef;
      candidateCount = adapterResult.candidateCount;
      if (adapterResult.accessMode !== "read") {
        outcome = "blocked_mutation";
        reasonCode = reasonCodeForOutcome(outcome);
      } else if (adapterResult.outcome === "resolved") {
        discoveredContext[prerequisite.key] = adapterResult.value;
      } else if (adapterResult.outcome === "unresolved_empty") {
        outcome = "unresolved_empty";
        reasonCode = reasonCodeForOutcome(outcome);
      } else if (adapterResult.outcome === "unresolved_ambiguous") {
        outcome = "unresolved_ambiguous";
        reasonCode = reasonCodeForOutcome(outcome);
      }
    }

    records.push({
      key: prerequisite.key,
      source: prerequisite.discoverySource,
      outcome,
      reasonCode,
      ...(typeof sourceRef === "string" ? { sourceRef } : {}),
      ...(typeof candidateCount === "number" ? { candidateCount } : {}),
      requiredUserAction: requiredUserActionForRecord({
        key: prerequisite.key,
        source: prerequisite.discoverySource,
        outcome,
      }),
    });
  }

  const firstBlocked = records.find((record) => record.outcome !== "resolved");
  if (firstBlocked) {
    return {
      status: "blocked",
      reasonCode: firstBlocked.reasonCode,
      discoveredContext,
      records,
      requiredUserAction: [...new Set(records.filter((record) => record.outcome !== "resolved").map((entry) => entry.requiredUserAction))],
    };
  }

  return {
    status: "resolved",
    reasonCode: "ok",
    discoveredContext,
    records,
    requiredUserAction: [],
  };
}

export async function buildReplayPreflightWithDiscovery(
  args: BuildReplayPreflightWithDiscoveryArgs,
): Promise<BuildReplayPreflightWithDiscoveryResult> {
  let mergedProvidedContext = { ...args.providedContext };
  let projectContextArg: BuildPreflightArgs["projectContext"] | undefined;
  const strictProbeBasesForConvergence: string[] = [];
  if (args.metadata.execution.probeVerification) {
    const probeBaseFromInitial = resolveProbeBaseUrl({
      providedContext: mergedProvidedContext,
      contract: args.contract,
    });
    if (probeBaseFromInitial) strictProbeBasesForConvergence.push(probeBaseFromInitial);
  }
  if (args.projectContextOptions) {
    const projectContext = await resolveProjectContextForRegression({
      ...args.projectContextOptions,
      strictProbeVerification: args.metadata.execution.probeVerification === true,
      ...(strictProbeBasesForConvergence.length > 0
        ? { strictProbeBaseUrls: strictProbeBasesForConvergence }
        : {}),
    });
    if (projectContext.status === "ok") {
      mergedProvidedContext = {
        ...projectContext.contextPatch,
        ...mergedProvidedContext,
      };
    } else {
      projectContextArg = {
        status: "blocked",
        reasonCode: projectContext.reasonCode,
        requiredUserAction: projectContext.requiredUserAction,
        ...(projectContext.missing ? { missing: projectContext.missing } : {}),
        ...(projectContext.checks ? { checks: projectContext.checks } : {}),
        ...(projectContext.nextAction ? { nextAction: projectContext.nextAction } : {}),
      };
    }
  }

  if (args.metadata.execution.probeVerification) {
    const probeBaseUrl = resolveProbeBaseUrl({
      providedContext: mergedProvidedContext,
      contract: args.contract,
    });
    if (probeBaseUrl) {
      const reachable = await isProbeBaseReachable(probeBaseUrl, args.timeoutMs ?? 2000);
      if (!reachable) {
        projectContextArg = {
          status: "blocked",
          reasonCode: "external_healthcheck_failed",
          checks: [`probe:${probeBaseUrl}=unreachable`],
          nextAction:
            "Start the target JVM with MCP javaagent enabled and confirm probe endpoint is reachable.",
          requiredUserAction: [
            `Probe endpoint unreachable at ${probeBaseUrl}. Start/restart runtime with javaagent and retry.`,
          ],
        };
      }
    }
  }

  const initialPreflight = buildReplayPreflight({
    ...args,
    providedContext: mergedProvidedContext,
    ...(projectContextArg ? { projectContext: projectContextArg } : {}),
  });
  const hasDiscoverablePending = initialPreflight.discoverablePending.length > 0;
  if (!hasDiscoverablePending) {
    return {
      preflight: initialPreflight,
      resolvedContext: { ...mergedProvidedContext },
      discovery: null,
    };
  }

  const discoveryArgs: ResolveDiscoverablePrerequisitesArgs = {
    metadata: args.metadata,
    contract: args.contract,
    providedContext: mergedProvidedContext,
    adapters: args.adapters,
  };
  if (typeof args.timeoutMs === "number") {
    discoveryArgs.timeoutMs = args.timeoutMs;
  }
  const discovery = await resolveDiscoverablePrerequisites(discoveryArgs);
  const mergedContext = mergeDiscoveredContext({
    providedContext: mergedProvidedContext,
    discoveredContext: discovery.discoveredContext,
  });

  if (discovery.status === "blocked") {
    const blockedStatus = statusForOutcome(discovery.records.find((record) => record.outcome !== "resolved")?.outcome ?? "blocked_runtime_error");
    const blockedPreflight: PreflightResult = {
      status: blockedStatus,
      reasonCode: discovery.reasonCode,
      missing: [],
      discoverablePending: [...initialPreflight.discoverablePending],
      prerequisiteResolution: [...initialPreflight.prerequisiteResolution],
      requiredUserAction: discovery.requiredUserAction,
    };
    return {
      preflight: blockedPreflight,
      resolvedContext: mergedContext,
      discovery,
    };
  }

  const finalPreflight = buildReplayPreflight({
    ...args,
    providedContext: mergedContext,
    ...(projectContextArg ? { projectContext: projectContextArg } : {}),
  });
  return {
    preflight: finalPreflight,
    resolvedContext: mergedContext,
    discovery,
  };
}
