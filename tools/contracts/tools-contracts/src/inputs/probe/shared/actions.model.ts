import * as z from "zod/v4";

export const ProbeActionSchema = z.enum([
  "actuate",
  "capture",
  "check",
  "profiler",
  "reset",
  "status",
  "wait_for_hit",
]);

export const PROBE_ACTION_ALLOWLIST = {
  probe: ["actuate", "capture", "check", "profiler", "reset", "status", "wait_for_hit"],
} as const;

export type ProbeAction = z.infer<typeof ProbeActionSchema>;
