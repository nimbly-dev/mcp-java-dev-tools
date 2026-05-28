import * as z from "zod/v4";
import { ExecutionExportInputSchema, type ExecutionExportInput } from "@/models/inputs/artifact_management/execution_export/input.model";
import { ProbeConfigInputSchema, type ProbeConfigInput } from "@/models/inputs/artifact_management/probe_config/input.model";
import { ProjectContextInputSchema, type ProjectContextInput } from "@/models/inputs/artifact_management/project_context/input.model";
import { RegressionPlanInputSchema, type RegressionPlanInput } from "@/models/inputs/artifact_management/regression_plan/input.model";
import { RunResultInputSchema, type RunResultInput } from "@/models/inputs/artifact_management/run_result/input.model";
import { ArtifactActionSchema, ArtifactTypeSchema, type ArtifactActionByType } from "@/models/inputs/artifact_management/shared/actions.model";

export const ArtifactManagementRequestSchema = z.discriminatedUnion("artifactType", [
  z.object({
    artifactType: z.literal("probe_config"),
    action: z.enum(["read", "validate", "upsert"]),
    input: ProbeConfigInputSchema,
  }),
  z.object({
    artifactType: z.literal("project_context"),
    action: z.enum(["read", "validate", "upsert", "list"]),
    input: ProjectContextInputSchema,
  }),
  z.object({
    artifactType: z.literal("regression_plan"),
    action: z.enum(["read", "validate", "upsert", "list"]),
    input: RegressionPlanInputSchema,
  }),
  z.object({
    artifactType: z.literal("run_result"),
    action: z.enum(["read", "list"]),
    input: RunResultInputSchema,
  }),
  z.object({
    artifactType: z.literal("execution_export"),
    action: z.enum(["read", "list", "generate"]),
    input: ExecutionExportInputSchema,
  }),
]);

export type ArtifactManagementRequest = z.infer<typeof ArtifactManagementRequestSchema>;

export type ArtifactManagementEnvelope = {
  artifactType: z.infer<typeof ArtifactTypeSchema>;
  action: z.infer<typeof ArtifactActionSchema>;
  input: ProbeConfigInput | ProjectContextInput | RegressionPlanInput | RunResultInput | ExecutionExportInput;
};

export type { ArtifactActionByType };

export const ArtifactManagementInputSchema = {
  artifactType: ArtifactTypeSchema.describe("Artifact class under .mcpjvm/** managed by this tool."),
  action: ArtifactActionSchema.describe("Requested artifact lifecycle action."),
  input: z.union([
    ProbeConfigInputSchema,
    ProjectContextInputSchema,
    RegressionPlanInputSchema,
    RunResultInputSchema,
    ExecutionExportInputSchema,
  ]),
} as const;
