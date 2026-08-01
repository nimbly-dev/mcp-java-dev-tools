import type { FailureInvestigationContext } from "@tools-contracts/failure-analysis";

import type { FailureAnalysisResponse } from "../models/failure_analysis.model";
import { redactFailureFingerprint } from "../shared/redact_failure_display";
import { postFailureSidecar } from "../shared/sidecar_failure_client";

export async function analyzeTraceAction(input: {
  trace: string;
  sidecarBaseUrl: string;
  sidecarAuthorization?: string | undefined;
  investigation?: FailureInvestigationContext | undefined;
  timeoutMs?: number | undefined;
}): Promise<FailureAnalysisResponse> {
  try {
    const response = await postFailureSidecar(
      input.sidecarBaseUrl,
      "__probe/failure/analyze",
      { trace: input.trace },
      input.timeoutMs,
      input.sidecarAuthorization,
    );
    if (response.status < 200 || response.status >= 300 || response.json === null) {
      return blockedResponse(response.status, response.json);
    }
    if (!isCompleteFingerprint(response.json.fingerprint)) {
      return incompleteResponse(response.json, input.investigation);
    }
    const structuredContent = {
      outcome: "ANALYZED",
      reasonCode: "ok",
      fingerprint: redactFailureFingerprint(response.json.fingerprint),
      investigationCandidates: response.json.investigationCandidates,
      dependencyBoundary: response.json.dependencyBoundary,
      exceptionSections: response.json.exceptionSections,
      incompleteReasons: response.json.reasons,
      ...(input.investigation ? { investigation: input.investigation } : {}),
    };
    return responseFor(
      structuredContent,
      "Failure fingerprint prepared. No diagnosis is claimed until runtime reproduction matches.",
    );
  } catch (error) {
    return blockedResponse(undefined, error instanceof Error ? { message: error.message } : null);
  }
}

function isCompleteFingerprint(value: unknown): boolean {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as Record<string, unknown>).complete === true
  );
}

function incompleteResponse(
  payload: Record<string, unknown>,
  investigation: FailureInvestigationContext | undefined,
): FailureAnalysisResponse {
  const structuredContent = {
    outcome: "INCONCLUSIVE",
    reasonCode: "failure_fingerprint_incomplete",
    fingerprint: redactFailureFingerprint(payload.fingerprint),
    investigationCandidates: payload.investigationCandidates,
    dependencyBoundary: payload.dependencyBoundary,
    exceptionSections: payload.exceptionSections,
    incompleteReasons: payload.reasons,
    diagnosisClaimed: false,
    ...(investigation ? { investigation } : {}),
  };
  return responseFor(
    structuredContent,
    "Failure fingerprint is incomplete. No diagnosis is claimed and runtime verification is blocked.",
  );
}

function blockedResponse(
  status: number | undefined,
  _payload: Record<string, unknown> | null,
): FailureAnalysisResponse {
  const structuredContent = {
    outcome: "BLOCKED_SIDECAR_UNAVAILABLE",
    reasonCode: "sidecar_failure_analysis_unavailable",
    ...(typeof status === "number" ? { httpStatus: status } : {}),
  };
  return responseFor(
    structuredContent,
    "Failure analysis is blocked because the Sidecar is unavailable or rejected the trace.",
  );
}

function responseFor(
  structuredContent: Record<string, unknown>,
  text: string,
): FailureAnalysisResponse {
  return { content: [{ type: "text", text }], structuredContent };
}
