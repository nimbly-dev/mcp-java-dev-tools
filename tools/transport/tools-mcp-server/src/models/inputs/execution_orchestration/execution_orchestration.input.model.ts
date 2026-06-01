import * as z from "zod/v4";
import { ExecutionOrchestrationActionSchema } from "@/models/inputs/execution_orchestration/shared/actions.model";

export const ExecutionOrchestrationPayloadSchema = z
  .object({
    projectName: z
      .string()
      .min(1)
      .describe("Required .mcpjvm project folder name for deterministic project selection."),
    executionProfile: z
      .string()
      .min(1)
      .describe("Required execution profile name under workspaces[].executionProfiles[]."),
  })
  .strict();

export const ExecutionOrchestrationRequestSchema = z
  .object({
    action: ExecutionOrchestrationActionSchema,
    input: ExecutionOrchestrationPayloadSchema,
  })
  .strict();

export type ExecutionOrchestrationRequest = z.infer<typeof ExecutionOrchestrationRequestSchema>;

export const ExecutionOrchestrationInputSchema = {
  action: ExecutionOrchestrationActionSchema.describe("Execution orchestration action."),
  input: ExecutionOrchestrationPayloadSchema,
} as const;

