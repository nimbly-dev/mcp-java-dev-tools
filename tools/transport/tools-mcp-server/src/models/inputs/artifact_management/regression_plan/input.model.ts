import * as z from "zod/v4";
import { ArtifactSelectQuerySchema, ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

const SectionWindowSchema = z.object({
  offset: z.number().int().min(0).optional(),
  limit: z.number().int().min(1).max(250).optional(),
});

export const RegressionPlanQuerySchema = ArtifactSelectQuerySchema.extend({
  prerequisites: SectionWindowSchema.optional(),
  steps: SectionWindowSchema.optional(),
});

export const RegressionPlanInputSchema = ProjectScopedInputSchema.extend({
  planName: z.string().optional(),
  payload: z
    .object({
      metadata: z.record(z.string(), z.unknown()).optional(),
      contract: z.record(z.string(), z.unknown()).optional(),
      plan: z.string().optional(),
    })
    .optional(),
  query: RegressionPlanQuerySchema.optional(),
});

export type RegressionPlanInput = z.infer<typeof RegressionPlanInputSchema>;
