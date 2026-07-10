import type { PreflightResult } from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import type {
  ReplayInvocationInput,
  ReplayInvocationResolution,
  ReplayReference,
  ReplayReferenceKind,
  ReplayUserMessage,
} from "@tools-regression-execution-plan-spec/models/regression_replay_invocation.model";

export type {
  ReplayInvocationInput,
  ReplayInvocationResolution,
  ReplayReference,
  ReplayReferenceKind,
  ReplayUserMessage,
} from "@tools-regression-execution-plan-spec/models/regression_replay_invocation.model";

const RUN_ID_PATTERN =
  /^(?:\d{2}-\d{2}-\d{4}-\d{2}-\d{2}-\d{2}(?:AM|PM)|\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z_\d{2}|\d{10,})$/;
const PLAN_NAME_PATTERN = /^[A-Za-z0-9._-]+$/;

function isNonBlank(value: string | undefined): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

export function resolveReplayInvocation(input: ReplayInvocationInput): ReplayInvocationResolution {
  const runId = input.runId?.trim();
  const planPath = input.planPath?.trim();
  const planName = input.planName?.trim();
  const latest = input.latest === true;

  if (isNonBlank(runId) && !RUN_ID_PATTERN.test(runId)) {
    return {
      status: "blocked_invalid",
      reasonCode: "invalid_run_id",
      selected: null,
      ignored: [],
      requiredUserAction: [
        "Provide runId in timestamp format: MM-DD-YYYY-hh-mm-ssAM (or legacy YYYY-MM-DDTHH-mm-ssZ_XX).",
      ],
    };
  }
  if (isNonBlank(planPath) && planPath.length < 3) {
    return {
      status: "blocked_invalid",
      reasonCode: "invalid_plan_path",
      selected: null,
      ignored: [],
      requiredUserAction: ["Provide a valid non-empty plan path."],
    };
  }
  if (isNonBlank(planName) && !PLAN_NAME_PATTERN.test(planName)) {
    return {
      status: "blocked_invalid",
      reasonCode: "invalid_plan_name",
      selected: null,
      ignored: [],
      requiredUserAction: ["Provide planName using letters, digits, dot, underscore, or hyphen."],
    };
  }

  const candidates: Array<ReplayReference | null> = [
    isNonBlank(runId) ? { kind: "run_id", value: runId } : null,
    isNonBlank(planPath) ? { kind: "plan_path", value: planPath } : null,
    isNonBlank(planName) ? { kind: "plan_name", value: planName } : null,
    latest ? { kind: "latest" } : null,
  ];
  const provided = candidates.filter((candidate): candidate is ReplayReference => candidate !== null);
  if (provided.length === 0) {
    return {
      status: "blocked_invalid",
      reasonCode: "replay_reference_missing",
      selected: null,
      ignored: [],
      requiredUserAction: ["Provide one replay reference: runId, planPath, planName, or latest=true."],
    };
  }
  const selected = provided[0] as ReplayReference;
  const ignored = provided.slice(1).map((reference) => reference.kind);
  return {
    status: "resolved",
    reasonCode: "ok",
    selected,
    ignored,
    requiredUserAction: [],
  };
}

function referenceLabel(reference: ReplayReference): string {
  if (reference.kind === "latest") return "latest run";
  return `${reference.kind}:${reference.value}`;
}

export function buildReplayUserMessage(
  preflight: PreflightResult,
  reference: ReplayReference,
  options?: { selectedPlanPath?: string; selectedRunId?: string },
): ReplayUserMessage {
  const suffixParts: string[] = [];
  if (isNonBlank(options?.selectedPlanPath)) suffixParts.push(`plan=${options?.selectedPlanPath}`);
  if (isNonBlank(options?.selectedRunId)) suffixParts.push(`run=${options?.selectedRunId}`);
  const suffix = suffixParts.length ? ` (${suffixParts.join(", ")})` : "";
  if (preflight.status === "ready") {
    return {
      status: "ready_to_execute",
      reasonCode: preflight.reasonCode,
      preflightStatus: preflight.status,
      summary: `Replay preflight ready for ${referenceLabel(reference)}${suffix}.`,
      missing: [],
      checks: [],
      nextActions: [],
    };
  }
  return {
    status: "blocked",
    reasonCode: preflight.reasonCode,
    preflightStatus: preflight.status,
    summary: `Replay preflight blocked (${preflight.status}) for ${referenceLabel(reference)}${suffix}.`,
    missing: [...preflight.missing],
    checks: [...(preflight.checks ?? [])],
    ...(typeof preflight.nextAction === "string" ? { nextAction: preflight.nextAction } : {}),
    nextActions: [...preflight.requiredUserAction],
  };
}
