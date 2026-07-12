import * as z from "zod/v4";
import {
  ArtifactSelectQuerySchema,
  ProjectScopedInputSchema,
} from "@/models/inputs/artifact_management/shared/common.model";

const SectionWindowSchema = z.object({
  offset: z.number().int().min(0),
  limit: z.number().int().min(1).max(250),
});

const WatcherFilterSchema = z
  .object({
    watcherId: z.string().min(1).optional(),
    watcherStatus: z
      .enum(["pass", "fail_assertion", "blocked_dependency", "blocked_runtime"])
      .optional(),
  })
  .optional();

const RunStateStatusSchema = z.enum([
  "pass",
  "fail",
  "blocked",
  "partial_fail",
  "in_progress",
  "executed",
  "skipped",
]);

const CorrelationDetailWindowSchema = z.object({
  offset: z.number().int(),
  limit: z.number().int(),
});
const CorrelationStateFiltersSchema = z
  .object({
    planName: z.string().optional(),
    runId: z.string().optional(),
    suiteRunId: z.string().optional(),
    correlationSessionId: z.string().optional(),
    status: z.union([z.string(), z.array(z.string())]).optional(),
    reasonCode: z.string().optional(),
    keyType: z.string().optional(),
    keyValueExact: z.string().optional(),
    keyValueSha256: z.string().optional(),
    strictLineKey: z.string().optional(),
    probeId: z.string().optional(),
    logicalServiceId: z.string().optional(),
    runtimeInstanceId: z.string().optional(),
    startedFromEpochMs: z.number().int().nonnegative().optional(),
    startedToEpochMs: z.number().int().nonnegative().optional(),
    correlatedFromEpochMs: z.number().int().nonnegative().optional(),
    correlatedToEpochMs: z.number().int().nonnegative().optional(),
  })
  .passthrough();
const CorrelationStateDetailSchema = z.object({
  select: z.array(z.string()),
  keys: CorrelationDetailWindowSchema.optional(),
  lineExpectations: CorrelationDetailWindowSchema.optional(),
  probeObservations: CorrelationDetailWindowSchema.optional(),
});

export const RunResultQuerySchema = ArtifactSelectQuerySchema.extend({
  filters: CorrelationStateFiltersSchema.optional(),
  sort: z
    .object({ field: z.literal("startedAtEpochMs"), direction: z.enum(["asc", "desc"]) })
    .optional(),
  page: z
    .object({
      pageSize: z.number().int().min(1).max(100).default(25),
      cursor: z.string().min(1).nullable().optional(),
    })
    .optional(),
  detail: CorrelationStateDetailSchema.optional(),
  planName: z.string().min(1).optional(),
  runId: z.string().min(1).optional(),
  suiteRunId: z.string().min(1).optional(),
  executionProfile: z.string().min(1).optional(),
  status: z.union([RunStateStatusSchema, z.array(RunStateStatusSchema).min(1).max(7)]).optional(),
  activePhase: z.enum(["trigger", "watchers", "external_verification"]).optional(),
  startedFromEpochMs: z.number().int().nonnegative().optional(),
  startedToEpochMs: z.number().int().nonnegative().optional(),
  completedFromEpochMs: z.number().int().nonnegative().optional(),
  completedToEpochMs: z.number().int().nonnegative().optional(),
  sortDirection: z.enum(["asc", "desc"]).default("desc"),
  pageSize: z.number().int().min(1).max(100).default(25),
  cursor: z.string().min(1).optional(),
  watchers: SectionWindowSchema.optional(),
  watcherEvidence: SectionWindowSchema.optional(),
  watcherFilter: WatcherFilterSchema,
})
  .passthrough()
  .superRefine((query, ctx) => {
    if (
      query.startedFromEpochMs !== undefined &&
      query.startedToEpochMs !== undefined &&
      query.startedFromEpochMs > query.startedToEpochMs
    )
      ctx.addIssue({
        code: "custom",
        path: ["startedFromEpochMs"],
        message: "startedFromEpochMs cannot exceed startedToEpochMs",
      });
    if (
      query.completedFromEpochMs !== undefined &&
      query.completedToEpochMs !== undefined &&
      query.completedFromEpochMs > query.completedToEpochMs
    )
      ctx.addIssue({
        code: "custom",
        path: ["completedFromEpochMs"],
        message: "completedFromEpochMs cannot exceed completedToEpochMs",
      });
    const selectors = Array.isArray(query.select) ? query.select : [];
    if (selectors.includes("watchers") && !query.watchers) {
      ctx.addIssue({
        code: "custom",
        path: ["watchers"],
        message:
          "query.watchers with required offset and limit is required when selecting watchers",
      });
    }
    if (selectors.includes("watcherEvidence") && !query.watcherEvidence) {
      ctx.addIssue({
        code: "custom",
        path: ["watcherEvidence"],
        message:
          "query.watcherEvidence with required offset and limit is required when selecting watcherEvidence",
      });
    }
  });

export const RunResultInputSchema = ProjectScopedInputSchema.extend({
  planName: z.string().optional(),
  runId: z.string().optional(),
  executionProfile: z.string().optional(),
  strict: z.boolean().optional(),
  query: RunResultQuerySchema.optional(),
  stateSurface: z.enum(["run_state", "correlation_state"]).optional(),
});

export type RunResultInput = z.infer<typeof RunResultInputSchema>;
