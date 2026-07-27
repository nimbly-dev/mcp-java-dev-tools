import type { RouteSynthesisTargetInferenceDeps } from "@tools-feature-route-synthesis";
import { deriveNextActionCode, normalizeReasonMeta } from "@tools-core/failure_diagnostics";
import {
  RuntimeProbeUnreachableError,
  selectRuntimeValidatedLine,
} from "./inference/runtime_line_selection.util";

export { RuntimeProbeUnreachableError } from "./inference/runtime_line_selection.util";

export async function selectTargetRuntimeLine(args: {
  probeKey?: string;
  probeBaseUrl: string;
  startLine: number;
  endLine: number;
  config: RouteSynthesisTargetInferenceDeps["config"];
}): Promise<{
  firstExecutableLine: number | null;
  lineSelectionStatus: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
}> {
  if (!args.probeKey) {
    return {
      firstExecutableLine: null,
      lineSelectionStatus: "unresolved",
    };
  }
  if (!args.probeBaseUrl || !args.config.probeStatusPath) {
    throw new RuntimeProbeUnreachableError(
      "Probe runtime config unavailable (missing probeBaseUrl/probeStatusPath).",
    );
  }
  return selectRuntimeValidatedLine({
    probeBaseUrl: args.probeBaseUrl,
    probeStatusPath: args.config.probeStatusPath,
    probeKey: args.probeKey,
    startLine: args.startLine,
    endLine: args.endLine,
    maxScanLines: args.config.probeLineSelectionMaxScanLines,
  });
}

export const TARGET_INFER_REASON_META_KEYS = [
  "failedStep",
  "classHint",
  "methodHint",
  "lineHint",
  "discoveryMode",
  "candidateCount",
  "resolvedCandidateCount",
] as const;

export function runtimeUnavailableResponse(args: {
  rootAbs: string;
  hints: Record<string, unknown>;
  reason: string;
}) {
  const reasonCode = "runtime_unreachable";
  const structuredContent = {
    resultType: "report",
    status: reasonCode,
    reasonCode,
    nextActionCode: deriveNextActionCode(reasonCode),
    failedStep: "line_validation",
    projectRoot: args.rootAbs,
    hints: args.hints,
    reasonMeta: normalizeReasonMeta(
      { failedStep: "line_validation", ...args.hints },
      TARGET_INFER_REASON_META_KEYS,
    ),
    reason: args.reason,
    nextAction:
      "Verify probe runtime reachability (probe base URL/port) and rerun route_synthesis with action=infer_target or action=class_methods.",
    evidence: [args.reason],
    attemptedStrategies: ["runtime_line_validation"],
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}
