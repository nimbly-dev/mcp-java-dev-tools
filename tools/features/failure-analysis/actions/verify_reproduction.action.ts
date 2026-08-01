import type {
  FailureAnalysisRequest,
  FailureInvestigationContext,
  FailureTerminalState,
} from "@tools-contracts/failure-analysis";

import type { FailureAnalysisResponse } from "../models/failure_analysis.model";
import { redactFailureFingerprint } from "../shared/redact_failure_display";
import { postFailureSidecar } from "../shared/sidecar_failure_client";

type VerifyReproductionInput = Extract<
  FailureAnalysisRequest,
  { action: "verify_reproduction" }
>["input"];

export async function verifyReproductionAction(
  input: VerifyReproductionInput,
): Promise<FailureAnalysisResponse> {
  if ("terminalState" in input) {
    return terminalResponse(input.terminalState, input.investigation);
  }
  try {
    const response = await postFailureSidecar(
      input.sidecarBaseUrl,
      "__probe/failure/verify",
      {
        captureId: input.captureId,
        expectedExceptionType: input.expectedFingerprint.exceptionType,
        expectedRootCauseType: input.expectedFingerprint.rootCauseType,
        expectedNearestApplicationMethodKey: input.expectedFingerprint.nearestApplicationMethodKey,
      },
      input.timeoutMs,
      input.sidecarAuthorization,
    );
    if (response.status < 200 || response.status >= 300 || response.json === null) {
      if (response.status === 404) return captureUnavailableResponse(input.captureId);
      return unavailableResponse(response.status);
    }
    const matched = response.json.outcome === "matched";
    const structuredContent = {
      outcome: matched ? "REPRODUCED" : "NOT_REPRODUCED",
      reasonCode: matched ? "ok" : String(response.json.outcome ?? "inconclusive"),
      expectedFingerprint: input.expectedFingerprint,
      observedFingerprint: redactFailureFingerprint(response.json.observedFingerprint),
      lineHit: input.lineHit,
      attemptEvidence: { captureId: input.captureId, sidecarOutcome: response.json.outcome },
      cleanupStatus: "external_workflow_owned",
      ...(input.investigation ? { investigation: input.investigation } : {}),
      ...(matched ? {} : { diagnosisClaimed: false }),
    };
    const text = matched
      ? "Failure reproduced with matching runtime fingerprint and Strict Line Key evidence."
      : "Failure was not reproduced. No diagnosis is claimed.";
    return { content: [{ type: "text", text }], structuredContent };
  } catch {
    return unavailableResponse(undefined);
  }
}

function terminalResponse(
  terminalState: FailureTerminalState,
  investigation: FailureInvestigationContext | undefined,
): FailureAnalysisResponse {
  const structuredContent = {
    outcome: terminalState.outcome,
    reasonCode: terminalState.reasonCode,
    attemptEvidence: { attemptCount: terminalState.attemptCount },
    cleanupStatus: terminalState.cleanupStatus,
    diagnosisClaimed: false,
    ...(investigation ? { investigation } : {}),
  };
  return {
    content: [
      {
        type: "text",
        text: "Failure investigation ended without runtime reproduction. No diagnosis is claimed.",
      },
    ],
    structuredContent,
  };
}

function captureUnavailableResponse(captureId: string): FailureAnalysisResponse {
  const structuredContent = {
    outcome: "INCONCLUSIVE",
    reasonCode: "capture_not_found",
    attemptEvidence: { captureId },
    diagnosisClaimed: false,
  };
  return {
    content: [
      {
        type: "text",
        text: "Failure verification is inconclusive because the capture is unavailable.",
      },
    ],
    structuredContent,
  };
}

function unavailableResponse(status: number | undefined): FailureAnalysisResponse {
  const structuredContent = {
    outcome: "BLOCKED_SIDECAR_UNAVAILABLE",
    reasonCode: "sidecar_failure_verification_unavailable",
    ...(typeof status === "number" ? { httpStatus: status } : {}),
  };
  return {
    content: [
      { type: "text", text: "Failure verification is blocked because the Sidecar is unavailable." },
    ],
    structuredContent,
  };
}
