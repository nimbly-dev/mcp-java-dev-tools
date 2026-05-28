import * as z from "zod/v4";
import { ArtifactSelectQuerySchema, ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

export const RegressionPlanInputSchema = ProjectScopedInputSchema.extend({
  planName: z.string().optional(),
  payload: z
    .object({
      metadata: z.record(z.string(), z.unknown()).optional(),
      contract: z.record(z.string(), z.unknown()).optional(),
      plan: z.string().optional(),
    })
    .optional(),
  query: ArtifactSelectQuerySchema.optional(),
});

export type RegressionPlanInput = z.infer<typeof RegressionPlanInputSchema>;
