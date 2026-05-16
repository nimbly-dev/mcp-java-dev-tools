import { promises as fs } from "node:fs";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import type {
  PlanContract,
  PlanMetadata,
  PlanStepCondition,
  PlanStepConditionPredicate,
  PlanStep,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import type {
  RegressionRunExecutionResult,
  RegressionRunStepResult,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { buildReplayPreflightWithDiscovery } from "@tools-regression-execution-plan-spec/regression_discovery_resolver.util";
import {
  applyStepExtract,
  buildTimestampRunId,
  resolvePrerequisiteContext,
  resolveStepTransport,
} from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";
import {
  deriveRunStatusFromStepOutcomes,
  evaluateStepExpectations,
} from "@tools-regression-execution-plan-spec/regression_expectation_evaluator.util";
import {
  createMcpWrappedTransportAdapter,
  createTransportRegistry,
  executeTransportWithRegistry,
} from "@tools-regression-execution-plan-spec/regression_transport_executor.util";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { writeRegressionRunArtifacts } from "@tools-regression-execution-plan-spec/regression_run_artifact_writer.util";

type ConditionReasonCode =
  | "step_condition_malformed"
  | "step_condition_operator_invalid"
  | "step_condition_forward_reference"
  | "step_condition_path_missing"
  | "step_condition_type_mismatch";

type McpToolInvoker = (args: {
  toolName: string;
  input: Record<string, unknown>;
}) => Promise<{
  structuredContent?: Record<string, unknown>;
}>;

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

export type ExecuteRegressionPlanWorkflowArgs = {
  workspaceRootAbs: string;
  planName: string;
  mcpInvoke: McpToolInvoker;
  providedContext?: Record<string, unknown>;
  runtimeContextName?: string;
  runtimeConfigOverride?: {
    requestTimeoutMs?: number;
    retryMax?: number;
  };
};

export type ExecuteRegressionPlanWorkflowResult =
  | {
      status: "blocked";
      preflight: ReturnType<typeof resolveBlockedShape>;
    }
  | {
      status: "executed";
      runId: string;
      runStatus: "pass" | "fail" | "blocked";
      artifacts: Awaited<ReturnType<typeof writeRegressionRunArtifacts>>;
      executionResult: RegressionRunExecutionResult;
    };

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function readByPath(input: Record<string, unknown>, pathKey: string): unknown {
  const segments = pathKey.split(".");
  let cursor: unknown = input;
  for (const segment of segments) {
    if (!cursor || typeof cursor !== "object" || Array.isArray(cursor)) return undefined;
    cursor = (cursor as Record<string, unknown>)[segment];
  }
  return cursor;
}

function resolveConditionLeftValue(args: {
  left: string;
  context: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  currentOrder: number;
}):
  | { ok: true; actual: unknown }
  | {
      ok: false;
      reasonCode: ConditionReasonCode;
    } {
  if (args.left.startsWith("context.")) {
    return {
      ok: true,
      actual: readByPath(args.context, args.left.slice("context.".length)),
    };
  }
  const stepMatch = args.left.match(/^step\[(\d+)\]\.(.+)$/);
  if (!stepMatch) {
    return { ok: false, reasonCode: "step_condition_path_missing" };
  }
  const stepOrder = Number(stepMatch[1]);
  const pathAfter = stepMatch[2];
  if (typeof pathAfter !== "string" || pathAfter.length === 0) {
    return { ok: false, reasonCode: "step_condition_path_missing" };
  }
  if (!Number.isFinite(stepOrder) || stepOrder < 1) {
    return { ok: false, reasonCode: "step_condition_type_mismatch" };
  }
  if (stepOrder >= args.currentOrder) {
    return { ok: false, reasonCode: "step_condition_forward_reference" };
  }
  const stepOutput = args.stepOutputsByOrder[stepOrder];
  if (!stepOutput) {
    return { ok: false, reasonCode: "step_condition_path_missing" };
  }
  return {
    ok: true,
    actual: readByPath(stepOutput, pathAfter),
  };
}

function evaluatePredicate(args: {
  condition: PlanStepConditionPredicate;
  context: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  currentOrder: number;
}): { status: true | false | "blocked_invalid"; reasonCode?: ConditionReasonCode } {
  const left = resolveConditionLeftValue({
    left: args.condition.left,
    context: args.context,
    stepOutputsByOrder: args.stepOutputsByOrder,
    currentOrder: args.currentOrder,
  });
  if (!left.ok) {
    return { status: "blocked_invalid", reasonCode: left.reasonCode };
  }
  if (args.condition.op === "exists") {
    return { status: typeof left.actual !== "undefined" };
  }
  if (args.condition.op === "equals") {
    return { status: isDeepStrictEqual(left.actual, args.condition.right) };
  }
  if (args.condition.op === "not_equals") {
    return { status: !isDeepStrictEqual(left.actual, args.condition.right) };
  }
  if (args.condition.op === "in") {
    if (!Array.isArray(args.condition.right)) {
      return { status: "blocked_invalid", reasonCode: "step_condition_type_mismatch" };
    }
    return {
      status: args.condition.right.some((item) => isDeepStrictEqual(item, left.actual)),
    };
  }
  return { status: "blocked_invalid", reasonCode: "step_condition_operator_invalid" };
}

function evaluateStepCondition(args: {
  when: PlanStepCondition;
  context: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  currentOrder: number;
}): { status: true | false | "blocked_invalid"; reasonCode?: ConditionReasonCode } {
  const node = args.when as unknown as Record<string, unknown>;
  if ("all" in node) {
    if (!Array.isArray(node.all) || node.all.length === 0) {
      return { status: "blocked_invalid", reasonCode: "step_condition_malformed" };
    }
    for (const child of node.all as PlanStepCondition[]) {
      const evalChild = evaluateStepCondition({
        when: child,
        context: args.context,
        stepOutputsByOrder: args.stepOutputsByOrder,
        currentOrder: args.currentOrder,
      });
      if (evalChild.status === "blocked_invalid") return evalChild;
      if (evalChild.status === false) return { status: false };
    }
    return { status: true };
  }
  if ("any" in node) {
    if (!Array.isArray(node.any) || node.any.length === 0) {
      return { status: "blocked_invalid", reasonCode: "step_condition_malformed" };
    }
    let hasTrue = false;
    for (const child of node.any as PlanStepCondition[]) {
      const evalChild = evaluateStepCondition({
        when: child,
        context: args.context,
        stepOutputsByOrder: args.stepOutputsByOrder,
        currentOrder: args.currentOrder,
      });
      if (evalChild.status === "blocked_invalid") return evalChild;
      if (evalChild.status === true) hasTrue = true;
    }
    return { status: hasTrue };
  }
  if ("not" in node) {
    const notCondition = node.not as PlanStepCondition;
    const evalNot = evaluateStepCondition({
      when: notCondition,
      context: args.context,
      stepOutputsByOrder: args.stepOutputsByOrder,
      currentOrder: args.currentOrder,
    });
    if (evalNot.status === "blocked_invalid") return evalNot;
    return { status: !evalNot.status };
  }
  return evaluatePredicate({
    condition: node as unknown as PlanStepConditionPredicate,
    context: args.context,
    stepOutputsByOrder: args.stepOutputsByOrder,
    currentOrder: args.currentOrder,
  });
}

function resolveBlockedShape(preflight: {
  status: string;
  reasonCode: string;
  missing: string[];
  checks?: string[];
  nextAction?: string;
  requiredUserAction: string[];
}) {
  return {
    status: preflight.status,
    reasonCode: preflight.reasonCode,
    missing: preflight.missing,
    checks: preflight.checks ?? [],
    ...(typeof preflight.nextAction === "string" ? { nextAction: preflight.nextAction } : {}),
    requiredUserAction: preflight.requiredUserAction,
  };
}

async function readJsonFile<T>(absPath: string): Promise<T> {
  const text = await fs.readFile(absPath, "utf8");
  return JSON.parse(text) as T;
}

function buildHttpPayload(args: {
  step: PlanStep;
  resolvedTransport: Record<string, unknown>;
  context: Record<string, unknown>;
}): Record<string, unknown> {
  const transportHttp =
    typeof args.resolvedTransport.http === "object" && args.resolvedTransport.http !== null
      ? { ...(args.resolvedTransport.http as Record<string, unknown>) }
      : {};
  if (!transportHttp.method) transportHttp.method = "GET";
  if (!transportHttp.url) {
    const base = asString(args.context.apiBaseUrl);
    const pathTemplate = asString(transportHttp.pathTemplate);
    if (base && pathTemplate) {
      transportHttp.url = `${base.replace(/\/$/, "")}${pathTemplate.startsWith("/") ? "" : "/"}${pathTemplate}`;
    }
  }
  if (typeof transportHttp.body === "object" && transportHttp.body !== null && !Array.isArray(transportHttp.body)) {
    transportHttp.body = JSON.stringify(transportHttp.body);
    const headers =
      typeof transportHttp.headers === "object" && transportHttp.headers !== null && !Array.isArray(transportHttp.headers)
        ? (transportHttp.headers as Record<string, unknown>)
        : {};
    const hasContentType = Object.keys(headers).some((key) => key.toLowerCase() === "content-type");
    if (!hasContentType) {
      headers["Content-Type"] = "application/json";
    }
    transportHttp.headers = headers;
  }
  return transportHttp;
}

export async function executeRegressionPlanWorkflow(
  args: ExecuteRegressionPlanWorkflowArgs,
): Promise<ExecuteRegressionPlanWorkflowResult> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs);
  const planRootAbs = path.join(plansRootAbs, args.planName);
  const metadata = await readJsonFile<PlanMetadata>(path.join(planRootAbs, "metadata.json"));
  const contract = await readJsonFile<PlanContract>(path.join(planRootAbs, "contract.json"));

  const projectName = path.basename(path.dirname(path.dirname(plansRootAbs)));
  const projectsFileAbs = path.join(args.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  const preflightWithDiscovery = await buildReplayPreflightWithDiscovery({
    metadata,
    contract,
    providedContext: args.providedContext ?? {},
    targetCandidateCount: 1,
    adapters: {},
    projectContextOptions: {
      workspaceRootAbs: args.workspaceRootAbs,
      projectsFileAbs,
      env: process.env,
      ...(typeof args.runtimeContextName === "string" ? { runtimeContextName: args.runtimeContextName } : {}),
      ...(args.runtimeConfigOverride ? { defaultsOverride: args.runtimeConfigOverride } : {}),
    },
  });

  if (preflightWithDiscovery.preflight.status !== "ready") {
    return {
      status: "blocked",
      preflight: resolveBlockedShape(preflightWithDiscovery.preflight as any),
    };
  }

  const now = new Date();
  const runId = buildTimestampRunId(now, 1);
  const startedAt = now.toISOString();

  const resolvedContextInitial = resolvePrerequisiteContext(
    contract.prerequisites,
    preflightWithDiscovery.resolvedContext,
  );

  const adapter = createMcpWrappedTransportAdapter(args.mcpInvoke);
  const registry = createTransportRegistry([adapter]);

  let resolvedContext = { ...resolvedContextInitial };
  const stepRows: RegressionRunStepResult[] = [];
  const stepOutputsByOrder: Record<number, Record<string, unknown>> = {};
  let hardRuntimeBlocker = false;
  for (const step of [...contract.steps].sort((a, b) => a.order - b.order)) {
    if (typeof step.when !== "undefined") {
      const conditionResult = evaluateStepCondition({
        when: step.when,
        context: resolvedContext,
        stepOutputsByOrder,
        currentOrder: step.order,
      });
      if (conditionResult.status === "blocked_invalid") {
        hardRuntimeBlocker = true;
        stepRows.push({
          order: step.order,
          id: step.id,
          status: "blocked_runtime",
          durationMs: 1,
          statusCode: 0,
          assertions: [],
          reasonCode: conditionResult.reasonCode ?? "step_condition_malformed",
          conditionEvaluation: {
            status: "blocked_invalid",
            reasonCode: conditionResult.reasonCode ?? "step_condition_malformed",
          },
        });
        break;
      }
      if (conditionResult.status === false) {
        stepRows.push({
          order: step.order,
          id: step.id,
          status: "skipped_condition_false",
          durationMs: 1,
          statusCode: 0,
          assertions: [],
          conditionEvaluation: {
            status: false,
          },
        });
        continue;
      }
    }

    const target = contract.targets[step.targetRef];
    const strictProbeKey = target?.runtimeVerification?.strictProbeKey;
    const targetProbeId = target?.runtimeVerification?.probeId;
    const strictProbeEnabled =
      metadata.execution.probeVerification === true &&
      typeof strictProbeKey === "string" &&
      strictProbeKey.trim().length > 0;

    if (strictProbeEnabled) {
      const resetIn: Record<string, unknown> = { key: strictProbeKey as string };
      if (typeof targetProbeId === "string" && targetProbeId.trim().length > 0) {
        resetIn.probeId = targetProbeId.trim();
      }
      const resetOut = await args.mcpInvoke({
        toolName: "probe_reset",
        input: resetIn,
      });
      const resetStructured = asRecord(resetOut.structuredContent);
      if (!resetStructured || "error" in resetStructured) {
        hardRuntimeBlocker = true;
        stepRows.push({
          order: step.order,
          id: step.id,
          status: "blocked_runtime",
          durationMs: 1,
          statusCode: 0,
          assertions: [],
          reasonCode: "probe_reset_failed",
        });
        break;
      }
    }

    const resolvedTransport = resolveStepTransport(step, resolvedContext);
    const payload =
      step.protocol === "http"
        ? buildHttpPayload({ step, resolvedTransport, context: resolvedContext })
        : ((resolvedTransport[step.protocol] as Record<string, unknown>) ?? {});
    const transport = await executeTransportWithRegistry({
      protocol: step.protocol as any,
      payload,
      registry,
    });
    const stepEnvelope: Record<string, unknown> = {
      status: transport.status === "pass" ? "pass" : "fail",
      response: {
        statusCode: transport.statusCode ?? 0,
        body: transport.bodyPreview ?? "",
      },
      transport: {
        durationMs: transport.durationMs,
        reasonCode: transport.reasonCode ?? null,
      },
    };

    if (strictProbeEnabled && transport.status === "pass") {
      const waitIn: Record<string, unknown> = {
        key: strictProbeKey as string,
        maxRetries: 5,
        pollIntervalMs: 300,
      };
      if (typeof targetProbeId === "string" && targetProbeId.trim().length > 0) {
        waitIn.probeId = targetProbeId.trim();
      }
      const waitOut = await args.mcpInvoke({
        toolName: "probe_wait_for_hit",
        input: waitIn,
      });
      const waitStructured = asRecord(waitOut.structuredContent);
      const waitResult = waitStructured ? asRecord(waitStructured.result) : null;
      const hit = waitResult?.hit === true;
      stepEnvelope.probe = {
        hit,
        key: strictProbeKey,
        ...(typeof targetProbeId === "string" ? { probeId: targetProbeId } : {}),
        coverage: hit ? "verified_line_hit" : "http_only_unverified_line",
      };
    }
    const evalResult = evaluateStepExpectations({
      stepResult: stepEnvelope,
      expectations: step.expect,
      httpFailure: transport.status === "fail_http",
      dependencyBlocked: transport.status === "blocked_invalid" || transport.status === "blocked_runtime",
    });
    stepRows.push({
      order: step.order,
      id: step.id,
      status: evalResult.status,
      durationMs: transport.durationMs,
      statusCode: transport.statusCode ?? 0,
      assertions: evalResult.assertions,
      reasonCode: transport.reasonCode,
      ...(typeof step.when === "undefined"
        ? {}
        : {
            conditionEvaluation: {
              status: true as const,
            },
          }),
    });
    stepOutputsByOrder[step.order] = stepEnvelope;
    resolvedContext = applyStepExtract(stepEnvelope, step.extract, resolvedContext);

    if (transport.status === "blocked_runtime" || transport.status === "blocked_invalid") {
      hardRuntimeBlocker = true;
      break;
    }
  }

  const ended = new Date();
  const runStatus = deriveRunStatusFromStepOutcomes({
    stepOutcomes: stepRows.map((row) => ({ status: row.status as any, required: true })),
    hardRuntimeBlocker,
  });
  const executionResult: RegressionRunExecutionResult = {
    status: runStatus,
    preflight: preflightWithDiscovery.preflight,
    startedAt,
    endedAt: ended.toISOString(),
    steps: stepRows,
  };

  const artifacts = await writeRegressionRunArtifacts({
    workspaceRootAbs: args.workspaceRootAbs,
    runId,
    planRef: { name: args.planName, path: planRootAbs },
    resolvedContext,
    secretContextKeys: contract.prerequisites.filter((entry) => entry.secret).map((entry) => entry.key),
    executionResult,
    evidence: {
      targetResolution: contract.targets.map((target, idx) => ({
        index: idx,
        type: target.type,
        selectors: target.selectors,
      })),
      executionSummary: {
        runStartEpoch: now.getTime(),
        runEndEpoch: ended.getTime(),
        runDurationMs: Math.max(1, ended.getTime() - now.getTime()),
      },
    },
    now,
  });

  return {
    status: "executed",
    runId,
    runStatus,
    artifacts,
    executionResult,
  };
}
