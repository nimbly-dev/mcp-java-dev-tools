import * as z from "zod/v4";

export const ProbeActuateInputSchema = {
  baseUrl: z
    .string()
    .optional()
    .describe("Optional direct Probe base URL override. Prefer probeId or Probe registry resolution."),
  probeId: z
    .string()
    .min(1)
    .optional()
    .describe("Optional named probe selector. If provided, takes precedence over baseUrl."),
  action: z
    .enum(["arm", "disarm"])
    .describe("Session-scoped actuation action."),
  sessionId: z
    .string()
    .min(1)
    .describe("Required actuation session identifier."),
  actuatorId: z.string().optional().describe("Optional actuator identifier for tracing/auditing."),
  targetKey: z
    .string()
    .optional()
    .describe("Required for action='arm'. Strict line key fully.qualified.Class#method:line."),
  returnBoolean: z
    .boolean()
    .optional()
    .describe("Required for action='arm'. true=force taken, false=force fallthrough."),
  ttlMs: z
    .number()
    .int()
    .positive()
    .optional()
    .describe("Required for action='arm'. Session TTL in milliseconds."),
  timeoutMs: z.number().int().positive().optional(),
} as const;
