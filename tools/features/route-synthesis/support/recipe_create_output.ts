export const RECIPE_REASON_META_KEYS = [
  "failedStep",
  "classHint",
  "methodHint",
  "lineHint",
  "selectedMode",
] as const;

export function isFqcn(value: string): boolean {
  const trimmed = value.trim();
  if (!trimmed.includes(".")) return false;
  const segments = trimmed.split(".");
  if (segments.some((segment) => segment.length === 0)) return false;
  return segments.every((segment) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(segment));
}

export function toActionCode(step: { title: string }): string {
  const title = step.title.trim().toLowerCase();
  if (title === "resolve authentication") return "resolve_auth";
  if (title === "request candidate missing") return "request_candidate_missing";
  if (title === "return report") return "return_report";
  if (title === "line target unresolved") return "line_target_unresolved";
  if (title === "reset probe baseline") return "probe_reset_baseline";
  if (title === "execute regression api check") return "execute_api_check";
  if (title === "verify api regression outcome") return "verify_api_regression";
  if (title === "execute probe trigger request") return "execute_probe_trigger";
  if (title === "verify single-line probe hit") return "verify_probe_hit";
  if (title === "verify api and line probe outcomes") return "verify_api_and_probe";
  if (title === "enable branch actuation") return "enable_actuation";
  if (title === "disable branch actuation") return "disable_actuation";
  return title.replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

export function compactRoutingReason(selectedMode: string): string {
  if (selectedMode === "regression") return "regression_no_probe";
  if (selectedMode === "single_line_probe") return "single_line_probe";
  if (selectedMode === "regression_plus_line_probe") return "regression_plus_line_probe";
  return "mode_selected";
}

export function compactExecutionPlanForOutput(args: {
  resultType: "recipe" | "report";
  executionPlan: {
    selectedMode: string;
    routingReason: string;
    steps: Array<{ phase: string; title: string; instruction: string }>;
    probeCallPlan: unknown;
  };
}) {
  if (args.resultType !== "report") return args.executionPlan;
  return compactExecutionPlanForText(args.executionPlan);
}

export function compactExecutionPlanForText(executionPlan: {
  selectedMode: string;
  routingReason: string;
  steps: Array<{ phase: string; title: string; instruction: string }>;
  probeCallPlan: unknown;
}) {
  return {
    selectedMode: executionPlan.selectedMode,
    routingReason: compactRoutingReason(executionPlan.selectedMode),
    steps: executionPlan.steps.map((step) => ({
      phase: step.phase,
      actionCode: toActionCode(step),
    })),
    probeCallPlan: executionPlan.probeCallPlan,
  };
}
