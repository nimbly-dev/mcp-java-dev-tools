import * as z from "zod/v4";

export const TargetInferInputSchema = {
  projectRootAbs: z
    .string()
    .describe(
      "Project root selected by orchestrator (absolute or relative to workspace); all inference stays scoped here.",
    ),
  additionalSourceRoots: z
    .array(z.string())
    .optional()
    .describe(
      "Optional additional source roots (absolute or relative to workspace) to include in static inference scope.",
    ),
  discoveryMode: z
    .enum(["ranked_candidates", "class_methods"])
    .optional()
    .describe(
      "Discovery mode. ranked_candidates (default) or class_methods (deterministic class inventory).",
    ),
  classHint: z
    .string()
    .optional()
    .describe("Exact class hint (prefer FQCN), e.g. com.example.catalog.CatalogShoeSpecifications"),
  methodHint: z.string().optional().describe("Method hint, e.g. finalPriceLte"),
  lineHint: z.number().int().positive().optional().describe("Optional line hint"),
  maxCandidates: z.number().int().positive().optional(),
  probeId: z
    .string()
    .optional()
    .describe("Optional probe registry id to force runtime line validation against a specific probe."),
  probeBaseUrl: z
    .string()
    .optional()
    .describe("Optional explicit probe base URL override for runtime line validation."),
} as const;
