import * as z from "zod/v4";

export const TransportExecuteInputSchema = {
  protocol: z.enum(["http", "grpc", "kafka", "custom"]).describe("Transport protocol."),
  request: z.record(z.string(), z.unknown()).describe("Protocol-specific execution request payload."),
  options: z
    .object({
      wrappedOnly: z
        .boolean()
        .optional()
        .describe("When true, fail-closed if non-wrapped executable transport is attempted."),
    })
    .optional(),
} as const;

