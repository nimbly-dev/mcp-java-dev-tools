import * as z from "zod/v4";
import { ArtifactSelectQuerySchema, ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

const SectionWindowSchema = z.object({
  offset: z.number().int().min(0),
  limit: z.number().int().min(1).max(250),
});

const WatcherFilterSchema = z
  .object({
    watcherId: z.string().min(1).optional(),
    watcherStatus: z.enum(["pass", "fail_assertion", "blocked_dependency", "blocked_runtime"]).optional(),
  })
  .optional();

export const RunResultQuerySchema = ArtifactSelectQuerySchema.extend({
  watchers: SectionWindowSchema.optional(),
  watcherEvidence: SectionWindowSchema.optional(),
  watcherFilter: WatcherFilterSchema,
}).superRefine((query, ctx) => {
  const selectors = Array.isArray(query.select) ? query.select : [];
  if (selectors.includes("watchers") && !query.watchers) {
    ctx.addIssue({
      code: "custom",
      path: ["watchers"],
      message: "query.watchers with required offset and limit is required when selecting watchers",
    });
  }
  if (selectors.includes("watcherEvidence") && !query.watcherEvidence) {
    ctx.addIssue({
      code: "custom",
      path: ["watcherEvidence"],
      message: "query.watcherEvidence with required offset and limit is required when selecting watcherEvidence",
    });
  }
});

export const RunResultInputSchema = ProjectScopedInputSchema.extend({
  planName: z.string().optional(),
  runId: z.string().optional(),
  executionProfile: z.string().optional(),
  query: RunResultQuerySchema.optional(),
});

export type RunResultInput = z.infer<typeof RunResultInputSchema>;
