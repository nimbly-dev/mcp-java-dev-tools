import * as z from "zod/v4";

export const ProbeCaptureGetInputSchema = {
  captureId: z.string().min(1).describe("Capture identifier returned by probe action=status capturePreview.captureId."),
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
