import * as z from "zod/v4";
import { ArtifactSelectQuerySchema, ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

export const ProjectContextInputSchema = ProjectScopedInputSchema.extend({
  payload: z.record(z.string(), z.unknown()).optional(),
  query: ArtifactSelectQuerySchema.extend({
    executionProfile: z.string().optional(),
  }).optional(),
});

export type ProjectContextInput = z.infer<typeof ProjectContextInputSchema>;
