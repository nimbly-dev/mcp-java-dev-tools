export { routeSynthesisDomain } from "./domain";
export { discoverClassMethods, inferTargets } from "./shared/target_inference";
export { generateRecipe } from "./shared/recipe_generation";
export type { ClassDiscoveryCandidate, ClassMethodSpan, InferredTarget } from "./models/target_inference.model";
export { buildJavaIndex } from "./support/inference/java_index.util";
export { enrichRuntimeCapture } from "./support/recipe_generate/runtime_capture_enrich.util";
export {
  MAX_ADDITIONAL_SOURCE_ROOTS,
  resolveAdditionalSourceRoots,
} from "./support/source_roots_resolve";
export { buildRecipeTemplateModel } from "./models/recipe_output_model";
export type * from "./models/route_synthesis.model";
export type * from "./models/recipe_generate.model";
export type RouteSynthesisFeatureModule = "route-synthesis";
