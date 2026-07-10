import type { AuthResolution } from "@tools-core/auth_resolution";
import type { IntentMode } from "@tools-core/recipe_constants.util";
import type {
  ExecutionReadiness,
  MissingExecutionInput,
  RecipeCandidate,
} from "@tools-core/recipe_types.util";

type ReadinessArgs = {
  selectedMode: IntentMode;
  lineTargetProvided: boolean;
  requestCandidate?: RecipeCandidate;
  deterministicRequestInferred?: boolean;
  auth: AuthResolution;
  actuationEnabled: boolean;
  actuationReturnBoolean?: boolean;
};

function modeNeedsLineTarget(mode: IntentMode): boolean {
  return mode === "single_line_probe" || mode === "regression_plus_line_probe";
}

function buildNextAction(missingInputs: MissingExecutionInput[]): string | undefined {
  if (missingInputs.length === 0) return undefined;
  const top = missingInputs
    .slice(0, 3)
    .map((m) => m.suggestedAction)
    .join(" ");
  return `Human input required before execution. ${top}`;
}

export function collectMissingExecutionInputs(args: ReadinessArgs): MissingExecutionInput[] {
  const missingInputs: MissingExecutionInput[] = [];
  const needsLineTarget = modeNeedsLineTarget(args.selectedMode);

  if (needsLineTarget && !args.lineTargetProvided) {
    missingInputs.push({
      category: "probe",
      field: "lineHint",
      reason: "Probe-capable modes require a strict Class#method:line target.",
      suggestedAction:
        "Provide lineHint (or explicit Class#method:line target) and rerun recipe generation.",
    });
  }

  if (!args.requestCandidate) {
    missingInputs.push({
      category: "request",
      field: "requestCandidate",
      reason: "API request candidate was not inferred from code context.",
      suggestedAction:
        "Refine classHint/methodHint/lineHint or provide explicit request context and rerun recipe generation.",
    });
  }

  if (args.auth.status === "needs_user_input") {
    const missingFields = args.auth.missing ?? ["authToken"];
    for (const field of missingFields) {
      missingInputs.push({
        category: "auth",
        field,
        reason: "Authentication input is required for execution.",
        suggestedAction: args.auth.nextAction,
      });
    }
  }

  const confirmationNotes = args.requestCandidate?.needsConfirmation ?? [];
  const hasBlockingConfirmation =
    confirmationNotes.length > 0 && !args.deterministicRequestInferred;
  if (hasBlockingConfirmation) {
    missingInputs.push({
      category: "confirmation",
      field: "requestCandidate.needsConfirmation",
      reason: `Request candidate has unresolved confirmations: ${confirmationNotes.join(" ")}`,
      suggestedAction:
        "Confirm the inferred endpoint/method assumptions before executing the trigger request.",
    });
  }

  if (
    args.actuationEnabled &&
    needsLineTarget &&
    typeof args.actuationReturnBoolean !== "boolean"
  ) {
    missingInputs.push({
      category: "actuation",
      field: "actuationReturnBoolean",
      reason: "Actuated flow requires explicit branch decision.",
      suggestedAction:
        "Provide actuationReturnBoolean=true|false to enable deterministic branch forcing.",
    });
  }

  return missingInputs;
}

export function buildExecutionReadiness(args: ReadinessArgs): {
  executionReadiness: ExecutionReadiness;
  missingInputs: MissingExecutionInput[];
  nextAction?: string;
} {
  const missingInputs = collectMissingExecutionInputs(args);
  const nextAction = buildNextAction(missingInputs);
  return {
    executionReadiness: missingInputs.length > 0 ? "needs_user_input" : "ready",
    missingInputs,
    ...(nextAction ? { nextAction } : {}),
  };
}
