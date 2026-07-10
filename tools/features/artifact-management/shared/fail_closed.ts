import { deriveNextActionCode } from "@/utils/failure_diagnostics.util";
import type { ArtifactActionResult } from "../actions/types";

export function buildFailClosedArtifactResponse(args: {
  reasonCode: string;
  reason: string;
  reasonMeta?: Record<string, unknown>;
}): ArtifactActionResult {
  const structuredContent: Record<string, unknown> = {
    resultType: "report",
    status: args.reasonCode,
    reasonCode: args.reasonCode,
    nextActionCode: deriveNextActionCode(args.reasonCode),
    reason: args.reason,
    ...(args.reasonMeta ? { reasonMeta: args.reasonMeta } : {}),
  };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

export function okArtifactResponse(body: Record<string, unknown>): ArtifactActionResult {
  return {
    content: [{ type: "text", text: JSON.stringify(body, null, 2) }],
    structuredContent: body,
  };
}
