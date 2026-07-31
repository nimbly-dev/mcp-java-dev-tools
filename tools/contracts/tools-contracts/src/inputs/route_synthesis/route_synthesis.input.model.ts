import * as z from "zod/v4";

import { RecipeGenerateInputSchema } from "@/models/inputs/recipe_generate.input.model";
import { TargetInferInputSchema } from "@/models/inputs/target_infer.input.model";
import { RouteSynthesisActionSchema } from "@/models/inputs/route_synthesis/shared/actions.model";

const TargetDiscoveryPayloadSchema = z
  .object({
    ...TargetInferInputSchema,
    discoveryMode: z.never().optional(),
  })
  .strict();

const CreateRecipePayloadSchema = z.object(RecipeGenerateInputSchema).strict();

function targetDiscoveryActionSchema(
  action: "infer_target" | "class_methods" | "discover_handlers",
) {
  return z.object({
    action: z.literal(action),
    input: TargetDiscoveryPayloadSchema,
  });
}

export const RouteSynthesisRequestSchema = z.discriminatedUnion("action", [
  targetDiscoveryActionSchema("infer_target"),
  targetDiscoveryActionSchema("class_methods"),
  targetDiscoveryActionSchema("discover_handlers"),
  z.object({
    action: z.literal("create_recipe"),
    input: CreateRecipePayloadSchema,
  }),
]);

export type RouteSynthesisRequest = z.infer<typeof RouteSynthesisRequestSchema>;

export const RouteSynthesisInputSchema = {
  action: RouteSynthesisActionSchema.describe("Requested route synthesis action."),
  input: z.union([TargetDiscoveryPayloadSchema, CreateRecipePayloadSchema]),
} as const;
