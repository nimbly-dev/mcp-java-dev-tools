import type { AuthResolution } from "@/models/auth_resolution.model";
import type { RoutingDecision } from "@tools-core/recipe_intent_routing.util";
import { redactSecret } from "@/utils/redaction.util";
import type {
  ProbeCallPlan,
  RecipeCandidate,
  RecipeExecutionPlan,
  RecipeExecutionStep,
} from "@tools-core/recipe_types.util";

function formatAuthHeaderHint(auth: AuthResolution): string {
  if (!auth.requestHeaders) {
    if (auth.required === "unknown" || auth.status === "unknown") {
      return `Auth unresolved. ${auth.nextAction}`;
    }
    if (auth.status === "needs_user_input") return `Auth unresolved. ${auth.nextAction}`;
    return "No auth headers required.";
  }
  return Object.entries(auth.requestHeaders)
    .map(([k, v]) => `${k}: ${redactSecret(v)}`)
    .join("; ");
}

function resolveLineTarget(args: {
  inferredTargetKey?: string;
  lineHint?: number;
}): string | undefined {
  if (!args.inferredTargetKey || typeof args.lineHint !== "number") return undefined;
  return `${args.inferredTargetKey}:${args.lineHint}`;
}

function buildResolveAuthStep(auth: AuthResolution): RecipeExecutionStep | undefined {
  if (auth.status !== "needs_user_input") return undefined;
  return {
    phase: "prepare",
    title: "Resolve authentication",
    instruction: auth.nextAction,
  };
}

function buildExecuteRequestStep(args: {
  requestCandidate: RecipeCandidate;
  auth: AuthResolution;
  title: string;
}): RecipeExecutionStep {
  const request = args.requestCandidate;
  return {
    phase: "execute",
    title: args.title,
    instruction:
      `${request.method} ${request.fullUrlHint} (headers: ${formatAuthHeaderHint(args.auth)})` +
      (request.bodyTemplate ? ` body: ${request.bodyTemplate}` : "") +
      " On Windows PowerShell, use curl.exe (not curl alias) and pass payload via a file or variable to avoid quoting issues.",
  };
}

function buildProbeCallPlan(args: {
  selectedMode: RoutingDecision["selectedMode"];
  hasLineTarget: boolean;
  hasRequestCandidate: boolean;
  actuationEnabled: boolean;
  actuationConfigured: boolean;
}): ProbeCallPlan {
  const out: ProbeCallPlan = {
    total: 0,
    verificationMethod: "probe_wait_for_hit",
    actuated: false,
    byTool: {
      probe_reset: 0,
      probe_wait_for_hit: 0,
      probe_get_status: 0,
      probe_enable: 0,
    },
  };

  const shouldRunProbeFlow =
    (args.selectedMode === "single_line_probe" ||
      args.selectedMode === "regression_plus_line_probe") &&
    args.hasLineTarget &&
    args.hasRequestCandidate;
  if (!shouldRunProbeFlow) return out;

  out.byTool.probe_reset = 1;
  out.byTool.probe_wait_for_hit = 1;
  if (args.actuationEnabled && args.actuationConfigured) {
    out.byTool.probe_enable = 2;
    out.actuated = true;
  }

  out.total =
    out.byTool.probe_reset +
    out.byTool.probe_wait_for_hit +
    out.byTool.probe_get_status +
    out.byTool.probe_enable;
  return out;
}

function buildActuateEnableStep(args: {
  lineTarget: string;
  returnBoolean: boolean;
  actuatorId?: string;
}): RecipeExecutionStep {
  return {
    phase: "prepare",
    title: "Enable branch actuation",
    instruction:
      `Call probe with action=actuate and input.action=arm targetKey=${args.lineTarget} returnBoolean=${args.returnBoolean}` +
      (args.actuatorId ? ` actuatorId=${args.actuatorId}` : "") +
      ".",
  };
}

function buildActuateDisableStep(args: {
  lineTarget: string;
  actuatorId?: string;
}): RecipeExecutionStep {
  return {
    phase: "cleanup",
    title: "Disable branch actuation",
    instruction:
      `Call probe with action=actuate and input.action=disarm targetKey=${args.lineTarget}` +
      (args.actuatorId ? ` actuatorId=${args.actuatorId}` : "") +
      " to clean up synthetic forcing.",
  };
}

function buildMissingRequestSteps(context: string): RecipeExecutionStep[] {
  return [
    {
      phase: "prepare",
      title: "Request candidate missing",
      instruction: `Cannot ${context} because request mapping is unavailable. Refine classHint/methodHint/lineHint or provide explicit request context.`,
    },
    {
      phase: "verify",
      title: "Return report",
      instruction: "Return report with status=api_request_not_inferred.",
    },
  ];
}

function buildRegressionApiSteps(args: {
  requestCandidate?: RecipeCandidate;
  auth: AuthResolution;
}): RecipeExecutionStep[] {
  const steps: RecipeExecutionStep[] = [];
  const authStep = buildResolveAuthStep(args.auth);
  if (authStep) steps.push(authStep);
  if (!args.requestCandidate)
    return steps.concat(buildMissingRequestSteps("run regression API checks"));

  steps.push(
    buildExecuteRequestStep({
      requestCandidate: args.requestCandidate,
      auth: args.auth,
      title: "Execute regression API check",
    }),
  );
  steps.push({
    phase: "verify",
    title: "Verify API regression outcome",
    instruction: "Validate HTTP code/response and service-side assertions for regression checks.",
  });
  return steps;
}

function buildSingleLineProbeSteps(args: {
  requestCandidate?: RecipeCandidate;
  auth: AuthResolution;
  lineTarget?: string;
  lineHint?: number;
  targetFile?: string;
  actuationEnabled: boolean;
  actuationConfigured: boolean;
  actuationReturnBoolean?: boolean;
  actuationActuatorId?: string;
}): RecipeExecutionStep[] {
  const steps: RecipeExecutionStep[] = [];
  const authStep = buildResolveAuthStep(args.auth);
  if (authStep) steps.push(authStep);
  if (!args.lineTarget || typeof args.lineHint !== "number") {
    steps.push({
      phase: "prepare",
      title: "Line target unresolved",
      instruction:
        "Probe flow requires a strict line target (Class#method:line). Refine inference input and rerun.",
    });
    steps.push({
      phase: "verify",
      title: "Return report",
      instruction: "Return report with status=target_not_inferred.",
    });
    return steps;
  }
  if (!args.requestCandidate)
    return steps.concat(buildMissingRequestSteps("trigger line probe verification"));

  if (
    args.actuationEnabled &&
    args.actuationConfigured &&
    typeof args.actuationReturnBoolean === "boolean"
  ) {
    steps.push(
      buildActuateEnableStep({
        lineTarget: args.lineTarget,
        returnBoolean: args.actuationReturnBoolean,
        ...(args.actuationActuatorId ? { actuatorId: args.actuationActuatorId } : {}),
      }),
    );
  }
  steps.push({
    phase: "prepare",
    title: "Reset probe baseline",
    instruction: `Call probe with action=reset and input.key=${args.lineTarget} before running trigger request.`,
  });
  steps.push(
    buildExecuteRequestStep({
      requestCandidate: args.requestCandidate,
      auth: args.auth,
      title: "Execute probe trigger request",
    }),
  );
  steps.push({
    phase: "verify",
    title: "Verify single-line probe hit",
    instruction:
      `Require line_hit on ${args.lineTarget} using probe action=wait_for_hit.` +
      (args.targetFile ? ` Correlate with ${args.targetFile}:${args.lineHint}.` : "") +
      " If probe action=wait_for_hit returns invalid_line_target, rebuild the app artifact and restart the JVM before rerun.",
  });
  if (args.actuationEnabled && args.actuationConfigured) {
    steps.push(
      buildActuateDisableStep({
        lineTarget: args.lineTarget,
        ...(args.actuationActuatorId ? { actuatorId: args.actuationActuatorId } : {}),
      }),
    );
  }
  return steps;
}

function buildCombinedSteps(args: {
  requestCandidate?: RecipeCandidate;
  auth: AuthResolution;
  lineTarget?: string;
  lineHint?: number;
  targetFile?: string;
  actuationEnabled: boolean;
  actuationConfigured: boolean;
  actuationReturnBoolean?: boolean;
  actuationActuatorId?: string;
}): RecipeExecutionStep[] {
  const steps: RecipeExecutionStep[] = [];
  const authStep = buildResolveAuthStep(args.auth);
  if (authStep) steps.push(authStep);
  if (!args.lineTarget || typeof args.lineHint !== "number") {
    steps.push({
      phase: "prepare",
      title: "Line target unresolved",
      instruction:
        "Combined mode requires a strict line target (Class#method:line). Refine inference input and rerun.",
    });
    steps.push({
      phase: "verify",
      title: "Return report",
      instruction: "Return report with status=target_not_inferred.",
    });
    return steps;
  }
  if (!args.requestCandidate)
    return steps.concat(buildMissingRequestSteps("run combined API + probe verification"));

  if (
    args.actuationEnabled &&
    args.actuationConfigured &&
    typeof args.actuationReturnBoolean === "boolean"
  ) {
    steps.push(
      buildActuateEnableStep({
        lineTarget: args.lineTarget,
        returnBoolean: args.actuationReturnBoolean,
        ...(args.actuationActuatorId ? { actuatorId: args.actuationActuatorId } : {}),
      }),
    );
  }
  steps.push({
    phase: "prepare",
    title: "Reset probe baseline",
    instruction: `Call probe with action=reset and input.key=${args.lineTarget} before regression API execution.`,
  });
  steps.push(
    buildExecuteRequestStep({
      requestCandidate: args.requestCandidate,
      auth: args.auth,
      title: "Execute regression API request",
    }),
  );
  steps.push({
    phase: "verify",
    title: "Verify API and line probe outcomes",
    instruction:
      `Require line_hit on ${args.lineTarget} via probe action=wait_for_hit and validate API regression assertions in the same run.` +
      (args.targetFile ? ` Correlate with ${args.targetFile}:${args.lineHint}.` : "") +
      " If probe action=wait_for_hit returns invalid_line_target, rebuild the app artifact and restart the JVM before rerun.",
  });
  if (args.actuationEnabled && args.actuationConfigured) {
    steps.push(
      buildActuateDisableStep({
        lineTarget: args.lineTarget,
        ...(args.actuationActuatorId ? { actuatorId: args.actuationActuatorId } : {}),
      }),
    );
  }
  return steps;
}

export function buildRecipeExecutionPlan(args: {
  decision: RoutingDecision;
  inferredTargetKey?: string;
  targetFile?: string;
  lineHint?: number;
  requestCandidate?: RecipeCandidate;
  auth: AuthResolution;
  actuationEnabled?: boolean;
  actuationReturnBoolean?: boolean;
  actuationActuatorId?: string;
}): RecipeExecutionPlan {
  const lineTarget = resolveLineTarget({
    ...(args.inferredTargetKey ? { inferredTargetKey: args.inferredTargetKey } : {}),
    ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
  });
  const actuationEnabled = args.actuationEnabled === true;
  const actuationConfigured = actuationEnabled && typeof args.actuationReturnBoolean === "boolean";

  if (args.decision.selectedMode === "regression") {
    return {
      selectedMode: args.decision.selectedMode,
      routingReason: args.decision.routingReason,
      steps: buildRegressionApiSteps({
        ...(args.requestCandidate ? { requestCandidate: args.requestCandidate } : {}),
        auth: args.auth,
      }),
      probeCallPlan: buildProbeCallPlan({
        selectedMode: args.decision.selectedMode,
        hasLineTarget: Boolean(lineTarget),
        hasRequestCandidate: Boolean(args.requestCandidate),
        actuationEnabled,
        actuationConfigured,
      }),
    };
  }

  if (args.decision.selectedMode === "single_line_probe") {
    return {
      selectedMode: args.decision.selectedMode,
      routingReason: args.decision.routingReason,
      steps: buildSingleLineProbeSteps({
        ...(args.requestCandidate ? { requestCandidate: args.requestCandidate } : {}),
        auth: args.auth,
        ...(lineTarget ? { lineTarget } : {}),
        ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
        ...(args.targetFile ? { targetFile: args.targetFile } : {}),
        actuationEnabled,
        actuationConfigured,
        ...(typeof args.actuationReturnBoolean === "boolean"
          ? { actuationReturnBoolean: args.actuationReturnBoolean }
          : {}),
        ...(args.actuationActuatorId ? { actuationActuatorId: args.actuationActuatorId } : {}),
      }),
      probeCallPlan: buildProbeCallPlan({
        selectedMode: args.decision.selectedMode,
        hasLineTarget: Boolean(lineTarget),
        hasRequestCandidate: Boolean(args.requestCandidate),
        actuationEnabled,
        actuationConfigured,
      }),
    };
  }

  return {
    selectedMode: args.decision.selectedMode,
    routingReason: args.decision.routingReason,
    steps: buildCombinedSteps({
      ...(args.requestCandidate ? { requestCandidate: args.requestCandidate } : {}),
      auth: args.auth,
      ...(lineTarget ? { lineTarget } : {}),
      ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
      ...(args.targetFile ? { targetFile: args.targetFile } : {}),
      actuationEnabled,
      actuationConfigured,
      ...(typeof args.actuationReturnBoolean === "boolean"
        ? { actuationReturnBoolean: args.actuationReturnBoolean }
        : {}),
      ...(args.actuationActuatorId ? { actuationActuatorId: args.actuationActuatorId } : {}),
    }),
    probeCallPlan: buildProbeCallPlan({
      selectedMode: args.decision.selectedMode,
      hasLineTarget: Boolean(lineTarget),
      hasRequestCandidate: Boolean(args.requestCandidate),
      actuationEnabled,
      actuationConfigured,
    }),
  };
}

