import * as z from "zod/v4";

export const ProbeStatusInputSchema = {
  key: z
    .string()
    .min(1)
    .optional()
    .describe("Probe key in strict line mode: fully.qualified.ClassName#methodName:lineNumber."),
  keys: z
    .array(z.string().min(1))
    .min(1)
    .optional()
    .describe("Batch probe keys in strict line mode. Use explicit Class#method:line keys."),
  lineHint: z
    .number()
    .int()
    .positive()
    .optional()
    .describe("Optional line hint. If provided with a method key, probes Class#method:<line>."),
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
