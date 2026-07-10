import { fetchJson } from "@/lib/http";
import { clampInt, DEFAULT_PROBE_TIMEOUT_MS, HARD_MAX_PROBE_TIMEOUT_MS } from "@/lib/safety";
import type { ProbeCaptureRecordPayload } from "@/models/probe_runtime_capture.model";
import type { ToolTextResponse } from "@tools-contracts/output";
import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";
import { compactCaptureRecord } from "@/utils/probe/compact_payload.util";
import { joinUrl, probeUnreachableMessage } from "@/utils/probe.util";
import { buildTextResponse } from "@/utils/probe/response_builders.util";

export async function probeCaptureGet(args: {
  captureId: string;
  baseUrl: string;
  capturePath: string;
  timeoutMs?: number;
}): Promise<ToolTextResponse> {
  const timeoutMs = clampInt(args.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS, 1_000, HARD_MAX_PROBE_TIMEOUT_MS);
  const captureId = args.captureId.trim();
  if (!captureId) {
    throw new Error("probe_get_capture requires captureId.");
  }
  const url = new URL(joinUrl(args.baseUrl, args.capturePath));
  url.searchParams.set("captureId", captureId);

  let res;
  try {
    res = await fetchJson(url.toString(), { method: "GET", timeoutMs });
  } catch (err) {
    throw new Error(probeUnreachableMessage(url.toString(), err));
  }

  const json = (res.json as Record<string, unknown> | null) ?? null;
  const capture =
    json && typeof json.capture === "object" && json.capture !== null
      ? ({ ...(json.capture as ProbeCaptureRecordPayload) } as ProbeCaptureRecordPayload)
      : null;
  const found = res.status >= 200 && res.status < 300 && capture !== null;
  const compactCapture = capture ? compactCaptureRecord(capture) : null;
  const executionPathCount =
    capture && Array.isArray((capture as Record<string, unknown>).executionPaths)
      ? ((capture as Record<string, unknown>).executionPaths as unknown[]).filter((v) => typeof v === "string").length
      : 0;

  const structuredContent: Record<string, unknown> = {
    request: {
      captureId,
      url: url.toString(),
      timeoutMs,
    },
    response: { status: res.status },
    result: found
      ? {
          found: true,
          capture: compactCapture,
        }
      : {
          found: false,
          reason: typeof json?.error === "string" ? json.error : "capture_unavailable",
          reasonCode: typeof json?.error === "string" ? json.error : "capture_unavailable",
          nextActionCode: deriveNextActionCode(
            typeof json?.error === "string" ? json.error : "capture_unavailable",
          ),
          reasonMeta: normalizeReasonMeta({ failedStep: "capture_lookup" }),
        },
  };

  const textPayload = {
    mode: "probe_get_capture",
    request: structuredContent.request,
    response: {
      status: res.status,
    },
    result: found
      ? {
          found: true,
          captureId: compactCapture?.captureId,
          methodKey: compactCapture?.methodKey,
          capturedAtEpoch: compactCapture?.capturedAtEpoch,
          executionStartedAtEpoch: compactCapture?.executionStartedAtEpoch,
          executionEndedAtEpoch: compactCapture?.executionEndedAtEpoch,
          executionDurationMs: compactCapture?.executionDurationMs,
          threadAllocatedBytesDelta: compactCapture?.threadAllocatedBytesDelta,
          argsCount: compactCapture?.argsCount,
          hasReturnValue: compactCapture?.hasReturnValue,
          hasThrownValue: compactCapture?.hasThrownValue,
          executionPathCount,
        }
      : {
          found: false,
          reason: typeof json?.error === "string" ? json.error : "capture_unavailable",
        },
    notes: "Use structuredContent.result.capture for full payload.",
  };
  return buildTextResponse(structuredContent, JSON.stringify(textPayload, null, 2));
}
