type RecipeTemplateModel = Record<string, string>;

type RecipeExecutionStep = {
  phase: "prepare" | "execute" | "verify" | "cleanup";
  title: string;
  instruction: string;
};

type RecipeCandidate = {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  path: string;
  queryTemplate: string;
  fullUrlHint: string;
  bodyTemplate?: string;
};

type TemplateAuth = {
  required: boolean | "unknown";
  status: "not_required" | "auto_resolved" | "needs_user_input" | "unknown";
  strategy: "none" | "bearer" | "basic" | "cookie" | "unknown";
  nextAction: string;
  requestHeaders?: Record<string, string>;
  missing?: string[];
  source?: string;
  notes: string[];
  loginHint?: {
    path: string;
    bodyTemplate: string;
  };
};

type GeneratedRecipeTemplateInput = {
  inferredTarget?: {
    key?: string;
  };
  requestCandidates: RecipeCandidate[];
  executionPlan: {
    selectedMode: string;
    routingReason: string;
    steps: RecipeExecutionStep[];
  };
  resultType: "recipe" | "report";
  status: string;
  selectedMode: string;
  nextAction?: string;
  failurePhase?: string;
  failureReasonCode?: string;
  reasonCode?: string;
  failedStep?: string;
  inferenceDiagnostics: {
    target: { matched: boolean };
    request: { matched: boolean; source?: string };
  };
  auth: TemplateAuth;
  notes: string[];
};

function formatSteps(steps: RecipeExecutionStep[]): string {
  if (steps.length === 0) return "No steps available.";
  return steps
    .map((s, index) => `${index + 1}. [${s.phase}] ${s.title}\n   ${s.instruction}`)
    .join("\n");
}

function redactSecret(value: string): string {
  if (!value) return value;
  const trimmed = value.trim();
  if (trimmed.length <= 8) return "***";
  return `${trimmed.slice(0, 4)}...${trimmed.slice(-2)}`;
}

function formatRecipeSteps(generated: GeneratedRecipeTemplateInput): string {
  const selectedModeLine = `Selected mode: ${generated.selectedMode}`;
  if (generated.resultType === "report") {
    const missingInput =
      generated.auth.status === "needs_user_input"
        ? `Missing Input: ${(generated.auth.missing ?? []).join(", ") || "authToken"}`
        : "Missing Input: (none)";
    return [
      "Reproduction report",
      selectedModeLine,
      `Status: ${generated.status}`,
      generated.nextAction ? `Next Action: ${generated.nextAction}` : "Next Action: (none)",
      missingInput,
      "",
      "Context:",
      formatSteps(generated.executionPlan.steps),
    ].join("\n");
  }
  return [
    "Reproduction execution plan",
    selectedModeLine,
    `Routing Reason: ${generated.executionPlan.routingReason}`,
    formatSteps(generated.executionPlan.steps),
  ].join("\n");
}

export function buildRecipeTemplateModel(args: {
  classHint: string;
  methodHint: string;
  lineHint?: number;
  generated: GeneratedRecipeTemplateInput;
}): RecipeTemplateModel {
  const { classHint, methodHint, lineHint, generated } = args;
  const inferredPath = generated.inferredTarget?.key
    ? generated.inferredTarget.key
    : `${classHint}.${methodHint}`;
  const firstReq = generated.requestCandidates[0];
  const requestDetails = firstReq
    ? `${firstReq.method} ${firstReq.fullUrlHint}${firstReq.bodyTemplate ? ` body=${firstReq.bodyTemplate}` : ""}`
    : generated.nextAction
      ? `Request candidate unavailable. ${generated.nextAction}`
      : "Request candidate unavailable. Provide workspace/class/method hints and rerun.";
  const authHeaders = generated.auth.requestHeaders
    ? Object.entries(generated.auth.requestHeaders)
        .map(([k, v]) => `${k}: ${redactSecret(v)}`)
        .join("; ")
    : "Not provided";
  const authMissing = generated.auth.missing?.join(", ") ?? "-";
  const authLoginPath = generated.auth.loginHint?.path ?? "Not inferred";
  const authLoginBody = generated.auth.loginHint?.bodyTemplate ?? "Not inferred";
  const recipeSteps = formatRecipeSteps(generated);
  const planMode = generated.executionPlan.selectedMode;
  const planReason = generated.executionPlan.routingReason;
  const successCriterion =
    generated.selectedMode === "regression"
      ? "api_regression_only"
      : typeof lineHint === "number"
        ? "line_hit (probe key Class#method:line; breakpoint optional)"
        : "line_key_required (Class#method:line)";

  return {
    "target.path": inferredPath,
    "target.class": classHint,
    "target.method": methodHint,
    "target.line_hint": typeof lineHint === "number" ? String(lineHint) : "Not provided",
    "probe.key": generated.inferredTarget?.key ?? "Not inferred",
    "probe.hit": "Not executed (recipe only)",
    "http.request": requestDetails,
    "http.method": firstReq?.method ?? "Not inferred",
    "http.path": firstReq?.path ?? "Not inferred",
    "http.query": firstReq?.queryTemplate ?? "Not inferred",
    "http.code": "Not executed",
    "http.response": "Not executed",
    execution_hit: "not_executed",
    api_outcome: "not_executed",
    repro_status: generated.status,
    "auth.required": String(generated.auth.required),
    "auth.status": generated.auth.status,
    "auth.strategy": generated.auth.strategy,
    "auth.next_action": generated.auth.nextAction,
    "auth.headers": authHeaders,
    "auth.missing": authMissing,
    "auth.source": generated.auth.source ?? "Not resolved",
    "auth.login.path": authLoginPath,
    "auth.login.body": authLoginBody,
    "recipe.mode": planMode,
    "recipe.mode_reason": planReason,
    "recipe.steps": recipeSteps,
    "run.duration": "Not measured",
    "run.notes":
      [
        `result_type=${generated.resultType}`,
        `status=${generated.status}`,
        `selected_mode=${generated.selectedMode}`,
        `routing_reason=${planReason}`,
        generated.failurePhase ? `failure_phase=${generated.failurePhase}` : "",
        generated.failureReasonCode ? `failure_reason=${generated.failureReasonCode}` : "",
        generated.reasonCode ? `synthesis_reason_code=${generated.reasonCode}` : "",
        generated.failedStep ? `synthesis_failed_step=${generated.failedStep}` : "",
        `inference_target_matched=${String(generated.inferenceDiagnostics.target.matched)}`,
        `inference_request_matched=${String(generated.inferenceDiagnostics.request.matched)}`,
        generated.inferenceDiagnostics.request.source
          ? `inference_request_source=${generated.inferenceDiagnostics.request.source}`
          : "",
        `success_criterion=${successCriterion}`,
        ...generated.notes,
        ...generated.auth.notes,
      ]
        .filter((s) => s.length > 0)
        .join(" | ") || "-",
  };
}

