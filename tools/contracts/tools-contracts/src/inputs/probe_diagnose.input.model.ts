import * as z from "zod/v4";

export const ProbeDiagnoseInputSchema = {
  baseUrl: z
    .string()
    .optional()
    .describe("Optional direct Probe base URL override. Prefer probeId or Probe registry resolution."),
  probeId: z
    .string()
    .min(1)
    .optional()
    .describe("Optional named probe selector. If provided, takes precedence over baseUrl."),
  http: z
    .object({
      headers: z
        .record(z.string(), z.string())
        .optional()
        .describe("Optional HTTP headers applied to probe reset/status requests."),
    })
    .optional()
    .describe("Optional HTTP transport overrides for protected probe endpoints."),
  timeoutMs: z.number().int().positive().optional(),
} as const;
