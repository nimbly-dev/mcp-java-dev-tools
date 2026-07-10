import * as z from "zod/v4";

export const RouteSynthesisActionSchema = z.enum([
  "infer_target",
  "class_methods",
  "create_recipe",
]);

export const ROUTE_SYNTHESIS_ACTION_ALLOWLIST = {
  route_synthesis: ["infer_target", "class_methods", "create_recipe"],
} as const;

export type RouteSynthesisAction = z.infer<typeof RouteSynthesisActionSchema>;
