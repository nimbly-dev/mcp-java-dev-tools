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
    suiteRunId: z
      .string()
      .min(1)
      .optional()
      .describe("Optional canonical suite run id to resume an in-progress execution_orchestration call."),
    maxPlansPerCall: z
      .number()
      .int()
      .min(1)
      .optional()
      .describe("Optional positive plan-slice size for synchronous resumable suite execution."),
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

