export type ProbeActionName =
  "actuate" | "capture" | "diagnose" | "profiler" | "reset" | "status" | "wait-for-hit";

export type ProbeActionMap = Readonly<Record<ProbeActionName, unknown>>;
