export type RouteSynthesisProbeRegistry = {
  probesById: {
    get(probeId: string): { baseUrl: string } | undefined;
  };
};

export function resolveProbeBaseUrl(args: {
  defaultProbeBaseUrl: string;
  probeId?: string;
  probeBaseUrl?: string;
  probeRegistry?: RouteSynthesisProbeRegistry;
}): { ok: true; probeBaseUrl: string } | { ok: false; reasonCode: string; reason: string } {
  if (typeof args.probeBaseUrl === "string" && args.probeBaseUrl.trim().length > 0) {
    return { ok: true, probeBaseUrl: args.probeBaseUrl.trim() };
  }
  if (typeof args.probeId === "string" && args.probeId.trim().length > 0) {
    const normalizedProbeId = args.probeId.trim();
    const probe = args.probeRegistry?.probesById.get(normalizedProbeId);
    if (!probe) {
      return {
        ok: false,
        reasonCode: "probe_id_unknown",
        reason: `probeId '${normalizedProbeId}' is not configured in active probe registry profile.`,
      };
    }
    return { ok: true, probeBaseUrl: probe.baseUrl };
  }
  return { ok: true, probeBaseUrl: args.defaultProbeBaseUrl };
}
