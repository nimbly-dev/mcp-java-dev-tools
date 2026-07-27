import type { AuthResolution } from "@tools-core/auth_resolution";
import type {
  InferenceFailurePhase,
  MissingExecutionInput,
  RecipeCandidate,
} from "@tools-core/recipe_types.util";
import type { SynthesisHttpTrigger } from "@tools-registry/models/synthesis/synthesizer_output.model";

export function deriveApplicationTypeFromSynthesizer(synthesizerUsed?: string): string | undefined {
  if (!synthesizerUsed) return undefined;
  const normalized = synthesizerUsed.trim().toLowerCase();
  if (!normalized) return undefined;
  if (normalized === "spring") return "spring";
  return normalized;
}

export function buildUnknownTargetAuth(): AuthResolution {
  return {
    required: "unknown",
    status: "unknown",
    strategy: "unknown",
    nextAction: "No target inferred; cannot resolve auth strategy yet.",
    notes: ["No method candidate matched current hints."],
  };
}

export function buildMissingRouteAuth(args: {
  genericAuth: { provided: boolean };
}): AuthResolution {
  return {
    required: "unknown",
    status: "unknown",
    strategy: "unknown",
    nextAction:
      "Entrypoint/auth requirements could not be inferred because no executable request candidate was synthesized. Resolve route synthesis first; then provide required auth headers/credentials if execution still needs them.",
    notes: [
      "No controller->method mapping was inferred, so route-level auth inference is unavailable.",
      ...(args.genericAuth.provided
        ? [
            "Caller provided auth inputs, but they cannot be validated until route synthesis succeeds.",
          ]
        : ["Auth requirements are unresolved until route synthesis succeeds."]),
    ],
  };
}

export function applyApiBasePathToCandidate(
  candidate: RecipeCandidate,
  apiBasePath?: string,
): RecipeCandidate {
  const pathWithBase = applyApiBasePathToPath(candidate.path, apiBasePath);
  const fullUrlHint = candidate.queryTemplate
    ? `${pathWithBase}?${candidate.queryTemplate}`
    : pathWithBase;
  return { ...candidate, path: pathWithBase, fullUrlHint };
}

export function applyApiBasePathToTrigger(
  trigger: SynthesisHttpTrigger,
  apiBasePath?: string,
): SynthesisHttpTrigger {
  const pathWithBase = applyApiBasePathToPath(trigger.path, apiBasePath);
  const fullUrlHint = trigger.queryTemplate
    ? `${pathWithBase}?${trigger.queryTemplate}`
    : pathWithBase;
  return { ...trigger, path: pathWithBase, fullUrlHint };
}

export function mapExecutionInputFailure(missingInputs: MissingExecutionInput[]):
  | {
      failurePhase: InferenceFailurePhase;
      failureReasonCode: string;
      failedStep: string;
    }
  | undefined {
  const first = missingInputs[0];
  if (!first) return undefined;
  switch (first.category) {
    case "probe":
      return {
        failurePhase: "target_inference",
        failureReasonCode: "line_target_required_for_probe_mode",
        failedStep: "intent_routing",
      };
    case "request":
      return {
        failurePhase: "request_inference",
        failureReasonCode: "request_candidate_missing",
        failedStep: "request_synthesis",
      };
    case "confirmation":
      return {
        failurePhase: "request_inference",
        failureReasonCode: "request_confirmation_required",
        failedStep: "request_confirmation",
      };
    case "actuation":
      return {
        failurePhase: "auth_resolution",
        failureReasonCode: "actuation_input_required",
        failedStep: "actuation_resolution",
      };
    case "auth":
    default:
      return {
        failurePhase: "auth_resolution",
        failureReasonCode: "auth_input_required",
        failedStep: "auth_resolution",
      };
  }
}

function normalizePath(pathValue: string): string {
  const trimmed = pathValue.trim();
  if (!trimmed) return "/";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function applyApiBasePathToPath(pathValue: string, apiBasePath?: string): string {
  const normalizedPath = normalizePath(pathValue);
  if (!apiBasePath || apiBasePath === "/") return normalizedPath;
  if (normalizedPath === apiBasePath || normalizedPath.startsWith(`${apiBasePath}/`)) {
    return normalizedPath;
  }
  return normalizedPath === "/" ? apiBasePath : `${apiBasePath}${normalizedPath}`;
}
