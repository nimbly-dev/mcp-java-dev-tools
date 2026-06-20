import * as z from "zod/v4";

export const ProbeProfilerInputSchema = {
  action: z
    .enum(["start", "stop", "reset", "status", "download"])
    .describe("Profiler lifecycle action routed to the Java probe sidecar."),
  sessionId: z
    .string()
    .min(1)
    .optional()
    .describe("Logical profiler session id. Required for start; optional for stop/status/reset."),
  event: z
    .string()
    .min(1)
    .optional()
    .describe("async-profiler event name. Defaults to cpu."),
  intervalNanos: z
    .number()
    .int()
    .positive()
    .optional()
    .describe("Optional async-profiler sampling interval in nanoseconds."),
  outputPath: z
    .string()
    .min(1)
    .optional()
    .describe("Optional absolute profiler output path. Required for download; optional for start/stop."),
  outputFormat: z
    .enum(["jfr"])
    .optional()
    .describe("Profiler output format. First implementation supports jfr only."),
  baseUrl: z
    .string()
    .optional()
    .describe("Optional direct Probe base URL override. Prefer probeId or Probe registry resolution."),
  probeId: z
    .string()
    .min(1)
    .optional()
    .describe("Optional named probe selector. If provided, takes precedence over baseUrl."),
  timeoutMs: z.number().int().positive().optional(),
} as const;
