import type { ProbeRegistry } from "@/config/probe-registry";
import type { ToolTextResponse } from "@tools-contracts/output";
import { buildTextResponse } from "@/utils/probe/response_builders.util";

function buildProbeSelectionBlockedResponse(args: {
  toolName: string;
  reasonCode: "probe_id_unknown" | "probe_id_required";
  probeId?: string;
  probeCount?: number;
}): ToolTextResponse {
  const nextActionCode =
    args.reasonCode === "probe_id_required" ? "provide_probe_id" : "select_registered_probe_id";
  const nextAction =
    args.reasonCode === "probe_id_required"
      ? "Provide probeId or baseUrl. Multi-probe profiles require explicit selection."
      : "Use artifact_management with artifactType=probe_config and action=read, then select a valid probeId.";
  const structuredContent: Record<string, unknown> = {
    resultType: "report",
    status: "probe_selection_failed",
    reasonCode: args.reasonCode,
    nextActionCode,
    nextAction,
    reasonMeta: {
      failedStep: "probe_registry_resolution",
      toolName: args.toolName,
      ...(args.probeId ? { probeId: args.probeId } : {}),
      ...(typeof args.probeCount === "number" ? { probeCount: args.probeCount } : {}),
    },
  };
  return buildTextResponse(structuredContent, JSON.stringify(structuredContent, null, 2));
}

export function resolveProbeBaseUrl(args: {
  toolName: string;
  probeId?: string | undefined;
  baseUrl?: string | undefined;
  defaultBaseUrl: string;
  probeRegistry?: ProbeRegistry | undefined;
}): { ok: true; baseUrl: string } | { ok: false; response: ToolTextResponse } {
  if (typeof args.probeId === "string" && args.probeId.trim().length > 0) {
    if (!args.probeRegistry) {
      return {
        ok: false,
        response: buildProbeSelectionBlockedResponse({
          toolName: args.toolName,
          reasonCode: "probe_id_required",
          probeId: args.probeId,
        }),
      };
    }
    const probe = args.probeRegistry.probesById.get(args.probeId.trim());
    if (!probe) {
      return {
        ok: false,
        response: buildProbeSelectionBlockedResponse({
          toolName: args.toolName,
          reasonCode: "probe_id_unknown",
          probeId: args.probeId,
        }),
      };
    }
    return { ok: true, baseUrl: probe.baseUrl };
  }

  if (typeof args.baseUrl === "string" && args.baseUrl.trim().length > 0) {
    return { ok: true, baseUrl: args.baseUrl.trim() };
  }

  if (args.probeRegistry) {
    const implicitProbeId = args.probeRegistry.implicitProbeId;
    const probe = typeof implicitProbeId === "string" ? args.probeRegistry.probesById.get(implicitProbeId) : undefined;
    if (probe) return { ok: true, baseUrl: probe.baseUrl };
    if (!args.defaultBaseUrl.trim()) {
      return {
        ok: false,
        response: buildProbeSelectionBlockedResponse({
          toolName: args.toolName,
          reasonCode: "probe_id_required",
          probeCount: args.probeRegistry.probesById.size,
        }),
      };
    }
  }

  return { ok: true, baseUrl: args.defaultBaseUrl };
}
