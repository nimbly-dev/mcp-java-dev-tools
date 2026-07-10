import * as z from "zod/v4";

export const ProbeResetInputSchema = {
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
  className: z
    .string()
    .min(1)
    .optional()
    .describe(
      "Class-scoped reset selector (fully-qualified class name). Resets all known line keys for the class.",
    ),
  lineHint: z
    .number()
    .int()
    .positive()
    .optional()
    .describe("Optional line hint. If provided with a method key, resets Class#method:<line>."),
  baseUrl: z.string().optional(),
  probeId: z
    .string()
    .min(1)
    .optional()
    .describe("Optional named probe selector. If provided, takes precedence over baseUrl."),
  timeoutMs: z.number().int().positive().optional(),
} as const;
