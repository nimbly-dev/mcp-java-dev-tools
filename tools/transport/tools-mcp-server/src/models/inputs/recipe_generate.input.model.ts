import * as z from "zod/v4";

export const RecipeGenerateInputSchema = {
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
  classHint: z
    .string()
    .describe("Fully-qualified class name (FQCN), e.g. com.example.catalog.CatalogShoeSpecifications"),
  methodHint: z.string().describe("Method hint, e.g. finalPriceLte"),
  lineHint: z.number().int().positive().optional(),
  mappingsBaseUrl: z
    .string()
    .optional()
    .describe(
      "Optional runtime mappings endpoint URL (for example http://127.0.0.1:8080/actuator/mappings). Used when discoveryPreference includes runtime mapping discovery.",
    ),
  discoveryPreference: z
    .enum(["static_only", "runtime_first", "runtime_only"])
    .optional()
    .describe(
      "Optional request discovery preference. static_only keeps AST/bytecode flow; runtime_first tries actuator mappings then falls back; runtime_only requires actuator mappings proof.",
    ),
  apiBasePath: z
    .string()
    .optional()
    .describe(
      "Optional API context/base path (for example /api/v1). Applied by orchestration to request candidates and trigger paths.",
    ),
  intentMode: z
    .enum(["line_probe", "regression"])
    .describe("Required user-facing execution intent."),
  authToken: z
    .string()
    .optional()
    .describe(
      "Optional bearer token. If auth is required and missing, tool returns needs_user_input.",
    ),
  authUsername: z
    .string()
    .optional()
    .describe("Optional auth username/email for login discovery flows."),
  authPassword: z.string().optional().describe("Optional auth password for login discovery flows."),
  actuationEnabled: z
    .boolean()
    .optional()
    .describe(
      "Optional flag to emit probe action=actuate arm/disarm steps around probe verification.",
    ),
  actuationReturnBoolean: z
    .boolean()
    .optional()
    .describe("Optional branch decision for actuated mode. Required when actuationEnabled=true."),
  actuationActuatorId: z
    .string()
    .optional()
    .describe("Optional actuator identifier to correlate enable/disable actuation calls."),
  outputTemplate: z
    .string()
    .optional()
    .describe(
      "Optional response template override. Placeholders include {{recipe.mode}}, {{recipe.mode_reason}}, {{recipe.steps}}, {{target.path}}, {{http.request}}, {{execution_hit}}, {{api_outcome}}, {{repro_status}}, {{auth.status}}, {{auth.strategy}}, {{auth.next_action}}, {{auth.headers}}, {{auth.missing}}, {{auth.source}}, {{auth.login.path}}, {{auth.login.body}}, {{probe.hit}}, {{http.code}}, {{http.response}}, {{run.duration}}, {{probe.key}}, {{run.notes}}.",
    ),
  probeId: z
    .string()
    .optional()
    .describe("Optional probe registry id to force runtime capture enrichment against a specific probe."),
  probeBaseUrl: z
    .string()
    .optional()
    .describe("Optional explicit probe base URL override for runtime capture enrichment."),
} as const;
