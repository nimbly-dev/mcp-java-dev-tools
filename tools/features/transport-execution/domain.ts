import { deriveNextActionCode, normalizeReasonMeta } from "@tools-core/failure_diagnostics";
import { executeHttpTransportRequest } from "./support/execute_http_request";

function elapsedMs(startEpochMs: number): number {
  const delta = Date.now() - startEpochMs;
  if (!Number.isFinite(delta) || delta <= 0) return 1;
  return Math.max(1, Math.round(delta));
}

export async function transportExecuteDomain(args: {
  protocol: "http" | "grpc" | "kafka" | "custom";
  request: Record<string, unknown>;
  wrappedOnly: boolean;
  allowNonWrappedExecutable: boolean;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  const startEpochMs = Date.now();

  if (args.wrappedOnly && args.allowNonWrappedExecutable) {
    const structuredContent = {
      status: "blocked_invalid",
      reasonCode: "wrapper_policy_violation",
      nextActionCode: deriveNextActionCode("wrapper_policy_violation"),
      reasonMeta: normalizeReasonMeta({
        failedStep: "transport_execute_policy",
        protocol: args.protocol,
      }),
      errorMessage:
        "wrappedOnly=true requested but probe registry allows non-wrapped executable transport.",
      durationMs: elapsedMs(startEpochMs),
      protocol: args.protocol,
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  if (args.protocol !== "http") {
    const structuredContent = {
      status: "blocked_invalid",
      reasonCode: "transport_not_supported",
      nextActionCode: deriveNextActionCode("transport_not_supported"),
      reasonMeta: normalizeReasonMeta({
        failedStep: "transport_execute_protocol",
        protocol: args.protocol,
      }),
      errorMessage: `Unsupported transport protocol '${args.protocol}'.`,
      durationMs: elapsedMs(startEpochMs),
      protocol: args.protocol,
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const structuredContent = await executeHttpTransportRequest({
    request: args.request,
    includeBody: false,
  });
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

