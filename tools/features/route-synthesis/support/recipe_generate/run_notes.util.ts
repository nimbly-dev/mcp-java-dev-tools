import type { AuthResolution } from "@tools-core/auth_resolution";
import type { RecipeCandidate, RecipeExecutionPlan } from "@tools-core/recipe_types.util";

export function buildRunNotes(args: {
  selectedMode: "regression" | "single_line_probe" | "regression_plus_line_probe";
  lineHint?: number;
  inferredLine?: number;
  bestRequest?: RecipeCandidate;
  matchedBranchCondition?: string;
  auth: AuthResolution;
  executionPlan: RecipeExecutionPlan;
  readiness: "ready" | "needs_user_input";
}): string[] {
  const notes: string[] = [];
  if (!args.bestRequest) {
    notes.push("No controller call mapping found in the selected module roots.");
  }
  if (args.selectedMode === "regression") {
    notes.push("Probe tools are disabled for this route.");
  } else {
    notes.push("Probe verification requires strict line key Class#method:line.");
    notes.push(
      "Interpretation guide: invalid_line_target implies runtime/source mismatch (rebuild + restart JVM); timeout_no_inline_hit implies no observed execution in-window.",
    );
  }
  if (
    typeof args.lineHint === "number" &&
    typeof args.inferredLine === "number" &&
    args.inferredLine !== args.lineHint
  ) {
    notes.push(
      `Provided line hint (${args.lineHint}) differs from inferred method start (${args.inferredLine}).`,
    );
  }
  if (args.matchedBranchCondition)
    notes.push(`Line/branch precondition hint: ${args.matchedBranchCondition}`);
  if (args.bestRequest?.needsConfirmation?.length) {
    notes.push(`Needs confirmation: ${args.bestRequest.needsConfirmation.join(" ")}`);
  }
  if (args.bestRequest?.assumptions?.length) {
    notes.push(`Assumed: ${args.bestRequest.assumptions.join(" ")}`);
  }
  if (args.auth.status === "needs_user_input" && args.bestRequest) {
    notes.push(
      `Missing input: ${(args.auth.missing ?? ["authToken"]).join(", ")}. Use the generated request skeleton after providing these values.`,
    );
  }
  notes.push(
    `probe_calls_total=${args.executionPlan.probeCallPlan.total} by_tool=${JSON.stringify(args.executionPlan.probeCallPlan.byTool)}`,
  );
  notes.push(`execution_readiness=${args.readiness}`);
  return notes;
}

