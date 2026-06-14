import type { AuthResolution } from "@/models/auth_resolution.model";
import type { FailureReasonMeta } from "@/models/failure_diagnostics.model";
import type { SynthesisHttpTrigger } from "@/models/synthesis/synthesizer_output.model";
import type { IntentMode, RecipeStatus } from "@tools-core/recipe_constants.util";
import type {
  ExecutionReadiness,
  InferenceDiagnostics,
  InferenceFailurePhase,
  MissingExecutionInput,
  RecipeCandidate,
  RecipeExecutionPlan,
} from "@tools-core/recipe_types.util";
import type { resolveAuthForRecipe } from "@/utils/recipe_generate/auth_resolve.util";
import type { SynthesizerRegistry } from "@tools-registry/plugin.loader";
import type {
  discoverClassMethods,
  inferTargets,
} from "@/tools/core/route_synthesis/shared/target_inference.util";

export type RecipeResultType = "recipe" | "report";

export type GenerateRecipeResult = {
  inferredTarget?: {
    key?: string;
    file: string;
    line?: number;
  };
  requestCandidates: RecipeCandidate[];
  executionPlan: RecipeExecutionPlan;
  resultType: RecipeResultType;
  status: RecipeStatus;
  selectedMode: IntentMode;
  lineTargetProvided: boolean;
  probeIntentRequested: boolean;
  executionReadiness: ExecutionReadiness;
  missingInputs: MissingExecutionInput[];
  nextAction?: string;
  failurePhase?: InferenceFailurePhase;
  failureReasonCode?: string;
  reasonCode?: string;
  nextActionCode?: string;
  reasonMeta?: FailureReasonMeta;
  failedStep?: string;
  synthesizerUsed?: string;
  applicationType?: string;
  attemptedStrategies: string[];
  evidence: string[];
  trigger?: SynthesisHttpTrigger;
  inferenceDiagnostics: InferenceDiagnostics;
  auth: AuthResolution;
  notes: string[];
};

export type GenerateRecipeDeps = {
  inferTargetsFn?: typeof inferTargets;
  discoverClassMethodsFn?: typeof discoverClassMethods;
  synthesizerRegistry?: SynthesizerRegistry;
  resolveAuthForRecipeFn?: typeof resolveAuthForRecipe;
};
