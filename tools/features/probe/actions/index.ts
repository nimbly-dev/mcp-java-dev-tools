import { executeProbeAction, type ProbeActionRequest as FeatureProbeActionRequest } from "./probe.action";

export type ProbeActionName = "actuate" | "capture" | "check" | "profiler" | "reset" | "status" | "wait_for_hit";

export type ProbeActionMap = Readonly<Record<ProbeActionName, unknown>>;

export function dispatchProbeAction(
  domain: ReturnType<typeof import("./probe.action").createProbeDomain>,
  request: FeatureProbeActionRequest,
) {
  return executeProbeAction(domain, request);
}
