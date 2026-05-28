import * as z from "zod/v4";
import { ArtifactSelectQuerySchema, ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

export const RunResultInputSchema = ProjectScopedInputSchema.extend({
  planName: z.string().optional(),
  runId: z.string().optional(),
  query: ArtifactSelectQuerySchema.optional(),
});

export type RunResultInput = z.infer<typeof RunResultInputSchema>;
