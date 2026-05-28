import * as z from "zod/v4";

export const ArtifactSelectQuerySchema = z.object({
  select: z.array(z.string()).optional(),
});

export const ProjectScopedInputSchema = z.object({
  projectName: z.string().optional(),
});
