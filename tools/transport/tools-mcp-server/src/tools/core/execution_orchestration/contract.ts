import { ExecutionOrchestrationInputSchema } from "@/models/inputs";

export const EXECUTION_ORCHESTRATION_TOOL = {
  name: "execution_orchestration",
  description:
    "Canonical runtime suite orchestrator for execution profiles. Executes ordered plans and persists canonical run artifacts with deterministic fail-closed behavior.",
  inputSchema: ExecutionOrchestrationInputSchema,
} as const;

