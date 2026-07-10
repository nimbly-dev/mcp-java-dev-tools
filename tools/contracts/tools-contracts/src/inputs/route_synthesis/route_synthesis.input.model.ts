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

function routeSynthesisActionSchema(action: "infer_target" | "class_methods") {
  return z.object({
    action: z.literal(action),
    input: TargetDiscoveryPayloadSchema,
  });
}

export const RouteSynthesisRequestSchema = z.discriminatedUnion("action", [
  routeSynthesisActionSchema("infer_target"),
  routeSynthesisActionSchema("class_methods"),
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
