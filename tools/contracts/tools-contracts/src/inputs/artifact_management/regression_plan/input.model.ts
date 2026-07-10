import * as z from "zod/v4";
import { ArtifactSelectQuerySchema, ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

const SectionWindowSchema = z.object({
  offset: z.number().int().min(0),
  limit: z.number().int().min(1).max(250),
});

export const RegressionPlanQuerySchema = ArtifactSelectQuerySchema.extend({
  prerequisites: SectionWindowSchema.optional(),
  steps: SectionWindowSchema.optional(),
}).superRefine((query, ctx) => {
  const selectors = Array.isArray(query.select) ? query.select : [];
  if (selectors.includes("prerequisites") && !query.prerequisites) {
    ctx.addIssue({
      code: "custom",
      path: ["prerequisites"],
      message: "query.prerequisites with required offset and limit is required when selecting prerequisites",
    });
  }
  if (selectors.includes("steps") && !query.steps) {
    ctx.addIssue({
      code: "custom",
      path: ["steps"],
      message: "query.steps with required offset and limit is required when selecting steps",
    });
  }
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
