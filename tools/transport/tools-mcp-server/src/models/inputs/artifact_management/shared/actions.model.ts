import * as z from "zod/v4";

export const ArtifactTypeSchema = z.enum([
  "probe_config",
  "project_context",
  "regression_plan",
  "run_result",
  "execution_export",
]);

export const ArtifactActionSchema = z.enum(["read", "validate", "upsert", "list", "generate", "reload"]);

export const ARTIFACT_ACTION_ALLOWLIST = {
  probe_config: ["read", "validate", "upsert", "reload"],
  project_context: ["read", "validate", "upsert", "list"],
  regression_plan: ["read", "validate", "upsert", "list"],
  run_result: ["read", "list"],
  execution_export: ["read", "list", "generate"],
} as const;

export type ArtifactType = z.infer<typeof ArtifactTypeSchema>;
export type ArtifactAction = z.infer<typeof ArtifactActionSchema>;

export type ArtifactActionByType = {
  [K in keyof typeof ARTIFACT_ACTION_ALLOWLIST]: (typeof ARTIFACT_ACTION_ALLOWLIST)[K][number];
};
