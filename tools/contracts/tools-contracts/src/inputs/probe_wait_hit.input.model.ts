import * as z from "zod/v4";

export const ProbeWaitHitInputSchema = {
  key: z
    .string()
    .min(1)
    .describe("Probe key in strict line mode: fully.qualified.ClassName#methodName:lineNumber."),
  lineHint: z
    .number()
    .int()
    .positive()
    .optional()
    .describe("Optional line hint. If provided with a method key, waits on Class#method:<line>."),
  baseUrl: z.string().optional(),
  probeId: z
    .string()
    .min(1)
    .optional()
    .describe("Optional named probe selector. If provided, takes precedence over baseUrl."),
  timeoutMs: z.number().int().positive().optional(),
  pollIntervalMs: z.number().int().positive().optional(),
  maxRetries: z.number().int().positive().optional(),
} as const;
