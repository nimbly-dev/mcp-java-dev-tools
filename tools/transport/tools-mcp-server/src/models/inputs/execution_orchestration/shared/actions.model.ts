import * as z from "zod/v4";

export const ExecutionOrchestrationActionSchema = z.enum(["execute"]);

export const EXECUTION_ORCHESTRATION_ACTION_ALLOWLIST = {
  execution_orchestration: ["execute"],
} as const;

export type ExecutionOrchestrationAction = z.infer<typeof ExecutionOrchestrationActionSchema>;

