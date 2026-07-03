import { promises as fs } from "node:fs";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import type {
  PlanContract,
  PlanCorrelationPolicy,
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
  applyStepExtractWithDiagnostics,
  buildTimestampRunId,
  resolvePrerequisiteContext,
  resolveStepTransport,
} from "@tools-regression-execution-plan-spec/regression_execution_plan_spec.util";
import { inferPlanApiBaseUrlFromProbeConfig } from "@tools-regression-execution-plan-spec/regression_plan_base_url.util";
import {
  deriveRunStatusFromStepOutcomes,
  evaluateStepExpectations,
} from "@tools-regression-execution-plan-spec/regression_expectation_evaluator.util";
import {
  normalizeHttpContextAliases,
  synthesizeHttpUrl,
} from "@tools-regression-execution-plan-spec/suite_http_request.util";
import { readValueByPath } from "@tools-regression-execution-plan-spec/suite_path_reader.util";
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

function resolveTransportReasonMeta(transport: { reasonMeta?: Record<string, unknown> }): Record<string, unknown> | undefined {
  return transport.reasonMeta && Object.keys(transport.reasonMeta).length > 0 ? transport.reasonMeta : undefined;
}

export type ExecuteRegressionPlanWorkflowArgs = {
  workspaceRootAbs: string;
  projectName?: string;
  planName: string;
  mcpInvoke: McpToolInvoker;
  providedContext?: Record<string, unknown>;
  runtimeContextName?: string;
  executionProfileName?: string;
  suiteRunId?: string;
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

type CorrelationKeyResolution = {
  keyValue?: string;
  sourceType?: "header" | "json_path" | "capture_field";
  sourcePath?: string;
  reasonCode?: "correlation_key_extraction_failed";
};

function tryParseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function correlationJsonBodyCandidatePaths(sourcePath: string): string[] {
  const candidates = new Set<string>([sourcePath]);
  if (sourcePath.startsWith("response.body.")) {
    candidates.add(sourcePath.slice("response.body.".length));
  }
  if (sourcePath.startsWith("response.bodyJson.")) {
    candidates.add(sourcePath.slice("response.bodyJson.".length));
  }
  if (sourcePath === "response.body" || sourcePath === "response.bodyJson") {
    candidates.add("");
  }
  return Array.from(candidates).filter((value) => value.length > 0);
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
      actual: readValueByPath(args.context, args.left.slice("context.".length)),
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
    actual: readValueByPath(stepOutput, pathAfter),
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
  const synthesizedUrl = synthesizeHttpUrl({
    url: transportHttp.url,
    apiBaseUrl: args.context.apiBaseUrl,
    pathTemplate: transportHttp.pathTemplate,
    path: transportHttp.path,
  });
  if (synthesizedUrl) transportHttp.url = synthesizedUrl;
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
  if (typeof transportHttp.timeoutMs !== "number" || !Number.isFinite(transportHttp.timeoutMs) || transportHttp.timeoutMs <= 0) {
    const defaultTimeoutMs = args.context["runtime.requestTimeoutMs"];
    if (typeof defaultTimeoutMs === "number" && Number.isFinite(defaultTimeoutMs) && defaultTimeoutMs > 0) {
      transportHttp.timeoutMs = Math.floor(defaultTimeoutMs);
    }
  }
  return transportHttp;
}

async function resolvePlanExecutionContext(args: {
  workspaceRootAbs: string;
  planName: string;
  resolvedContext: Record<string, unknown>;
}): Promise<Record<string, unknown>> {
  const normalizedContext = normalizeHttpContextAliases(args.resolvedContext);
  if (typeof normalizedContext.apiBaseUrl === "string" && normalizedContext.apiBaseUrl.trim().length > 0) {
    return normalizedContext;
  }
  const inferredApiBaseUrl = await inferPlanApiBaseUrlFromProbeConfig({
    workspaceRootAbs: args.workspaceRootAbs,
    planName: args.planName,
  });
  if (!inferredApiBaseUrl) {
    return normalizedContext;
  }
  return normalizeHttpContextAliases({
    ...normalizedContext,
    apiBaseUrl: inferredApiBaseUrl,
  });
}

function resolveCorrelationKeyValue(args: {
  correlation: PlanCorrelationPolicy;
  resolvedContext: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
}): CorrelationKeyResolution {
  const directValue = asString(args.correlation.key.value);
  if (directValue) {
    return {
      keyValue: directValue.replace(/\$\{([^}]+)\}/g, (_match, key) => {
        const resolved = args.resolvedContext[key];
        return typeof resolved === "undefined" || resolved === null ? "" : String(resolved);
      }),
    };
  }

  const source = args.correlation.key.source;
  if (!source || typeof source.path !== "string" || source.path.trim().length === 0) {
    return {};
  }
  const sourcePath = source.path.trim();

  const orders = Object.keys(args.stepOutputsByOrder)
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value))
    .sort((a, b) => b - a);
  for (const order of orders) {
    const stepOutput = args.stepOutputsByOrder[order];
    if (!stepOutput) continue;
    if (source.type === "header") {
      const headers = asRecord(readValueByPath(stepOutput, "response.headers"));
      if (!headers) continue;
      const headerName = sourcePath.toLowerCase();
      for (const [key, value] of Object.entries(headers)) {
        if (key.toLowerCase() === headerName) {
          const keyValue = asString(value);
          if (keyValue) {
            return {
              keyValue,
              sourceType: source.type,
              sourcePath,
            };
          }
        }
      }
      continue;
    }

    if (source.type === "json_path") {
      const fullPathValue = asString(readValueByPath(stepOutput, sourcePath));
      if (fullPathValue) {
        return {
          keyValue: fullPathValue,
          sourceType: source.type,
          sourcePath,
        };
      }
      const parsedBody = readValueByPath(stepOutput, "response.bodyJson");
      const parsedBodyRecord = asRecord(parsedBody);
      if (parsedBodyRecord) {
        for (const candidatePath of correlationJsonBodyCandidatePaths(sourcePath)) {
          const value = readValueByPath(parsedBodyRecord, candidatePath);
          const textValue = asString(value);
          if (textValue) {
            return {
              keyValue: textValue,
              sourceType: source.type,
              sourcePath,
            };
          }
        }
      }
      continue;
    }

    const value = readValueByPath(stepOutput, sourcePath);
    const textValue = asString(value);
    if (textValue) {
      return {
        keyValue: textValue,
        sourceType: source.type,
        sourcePath,
      };
    }
  }

  return {
    sourceType: source.type,
    sourcePath,
    reasonCode: "correlation_key_extraction_failed",
  };
}

function buildPlanCorrelationEvidence(args: {
  contract: PlanContract;
  resolvedContext: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  stepEventTimesByOrder: Record<number, number>;
}):
  | {
      correlationPolicy: Record<string, unknown>;
      correlationEvents: Array<Record<string, unknown>>;
    }
  | undefined {
  const correlation = args.contract.correlation;
  if (!correlation || correlation.enabled !== true) return undefined;

  const keyResolution = resolveCorrelationKeyValue({
    correlation,
    resolvedContext: args.resolvedContext,
    stepOutputsByOrder: args.stepOutputsByOrder,
  });
  const keyValue = keyResolution.keyValue;

  const correlationEvents: Array<Record<string, unknown>> = [];
  for (const step of [...args.contract.steps].sort((a, b) => a.order - b.order)) {
    const stepOutput = args.stepOutputsByOrder[step.order];
    const timestampEpochMs = args.stepEventTimesByOrder[step.order];
    if (!stepOutput || typeof timestampEpochMs !== "number") continue;
    if (asString(readValueByPath(stepOutput, "status")) !== "pass") continue;
    const target = args.contract.targets[step.targetRef];
    const probeId =
      asString(target?.runtimeVerification?.probeId) ??
      (correlation.probeIds.length === 1 ? correlation.probeIds[0] : undefined);
    if (!probeId) continue;
    correlationEvents.push({
      eventId: `${step.id}:${step.order}`,
      probeId,
      timestampEpochMs,
      keyType: correlation.key.type,
      ...(keyValue ? { keyValue } : {}),
      ...(typeof target?.runtimeVerification?.strictProbeKey === "string"
        ? { lineKey: target.runtimeVerification.strictProbeKey }
        : {}),
      eventType: step.protocol,
    });
  }

  return {
    correlationPolicy: {
      keyType: correlation.key.type,
      ...(keyValue ? { keyValue } : {}),
      ...(typeof keyResolution.sourceType === "string" ? { keySourceType: keyResolution.sourceType } : {}),
      ...(typeof keyResolution.sourcePath === "string" ? { keySourcePath: keyResolution.sourcePath } : {}),
      ...(typeof keyResolution.reasonCode === "string" ? { keyExtractionReasonCode: keyResolution.reasonCode } : {}),
      ...(typeof correlation.correlationSessionId === "string" && correlation.correlationSessionId.trim().length > 0
        ? { correlationSessionId: correlation.correlationSessionId.trim() }
        : {}),
      maxWindowMs: correlation.window.maxWindowMs,
      ...(Array.isArray(correlation.expectedFlow) ? { expectedFlow: correlation.expectedFlow } : {}),
      ...(typeof correlation.window.startEpochMs === "number"
        ? { startEpochMs: correlation.window.startEpochMs }
        : {}),
      ...(typeof correlation.window.endEpochMs === "number" ? { endEpochMs: correlation.window.endEpochMs } : {}),
    },
    correlationEvents,
  };
}

function isStepRequired(step: PlanStep | undefined): boolean {
  if (!step || !Array.isArray(step.expect) || step.expect.length === 0) {
    return true;
  }
  return step.expect.some((expectation) => expectation.required !== false);
}

export async function executeRegressionPlanWorkflow(
  args: ExecuteRegressionPlanWorkflowArgs,
): Promise<ExecuteRegressionPlanWorkflowResult> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
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
      ...(typeof args.executionProfileName === "string" ? { executionProfileName: args.executionProfileName } : {}),
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

  const resolvedContextInitial = normalizeHttpContextAliases({
    ...preflightWithDiscovery.resolvedContext,
    ...resolvePrerequisiteContext(
      contract.prerequisites,
      preflightWithDiscovery.resolvedContext,
    ),
  });

  const adapter = createMcpWrappedTransportAdapter(args.mcpInvoke);
  const registry = createTransportRegistry([adapter]);

  let resolvedContext = await resolvePlanExecutionContext({
    workspaceRootAbs: args.workspaceRootAbs,
    planName: args.planName,
    resolvedContext: { ...resolvedContextInitial },
  });
  const stepRows: RegressionRunStepResult[] = [];
  const stepOutputsByOrder: Record<number, Record<string, unknown>> = {};
  const stepEventTimesByOrder: Record<number, number> = {};
  let hardRuntimeBlocker = false;
  let eventCursorEpochMs = now.getTime();
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
        toolName: "probe",
        input: {
          action: "reset",
          input: resetIn,
        },
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
    const responseBody = transport.bodyText ?? transport.bodyPreview ?? "";
    const stepEnvelope: Record<string, unknown> = {
      status: transport.status === "pass" ? "pass" : "fail",
      response: {
        statusCode: transport.statusCode ?? 0,
        body: responseBody,
        ...(transport.headers ? { headers: transport.headers } : {}),
        ...(typeof responseBody === "string" ? { bodyJson: tryParseJson(responseBody) } : {}),
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
        toolName: "probe",
        input: {
          action: "wait_for_hit",
          input: waitIn,
        },
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
      transportFailure: transport.status === "fail_http",
      dependencyBlocked: transport.status === "blocked_invalid" || transport.status === "blocked_runtime",
    });
    const transportReasonMeta = resolveTransportReasonMeta(transport);
    const extractOutcome = applyStepExtractWithDiagnostics(stepEnvelope, step.extract, resolvedContext);
    const requiredExtractBlocked = extractOutcome.hasRequiredUnresolved;
    const extractPromotesBlock = requiredExtractBlocked && evalResult.status === "pass";
    const stepStatus = extractPromotesBlock ? "blocked_runtime" : evalResult.status;
    const unresolvedRequiredExtract = extractOutcome.outcomes.filter(
      (entry) => entry.required && entry.status === "unresolved",
    );
    let stepReasonCode: string | undefined;
    if (!extractPromotesBlock && evalResult.status !== "pass" && transport.reasonCode) {
      stepReasonCode = transport.reasonCode;
    }
    let stepReasonMeta: Record<string, unknown> | undefined;
    if (unresolvedRequiredExtract.length > 0) {
      stepReasonMeta = {
        ...(transportReasonMeta ?? {}),
        extract: unresolvedRequiredExtract,
      };
    } else if (evalResult.status !== "pass" && transportReasonMeta) {
      stepReasonMeta = transportReasonMeta;
    }
    stepRows.push({
      order: step.order,
      id: step.id,
      status: stepStatus,
      durationMs: transport.durationMs,
      statusCode: transport.statusCode ?? 0,
      ...(extractOutcome.outcomes.length > 0 ? { extract: extractOutcome.outcomes } : {}),
      assertions: evalResult.assertions,
      ...(extractPromotesBlock ? { reasonCode: "extract_path_missing" } : {}),
      ...(stepReasonCode ? { reasonCode: stepReasonCode } : {}),
      ...(stepReasonMeta ? { reasonMeta: stepReasonMeta } : {}),
      ...(typeof step.when === "undefined"
        ? {}
        : {
            conditionEvaluation: {
              status: true as const,
            },
          }),
    });
    stepOutputsByOrder[step.order] = stepEnvelope;
    stepEventTimesByOrder[step.order] = eventCursorEpochMs;
    eventCursorEpochMs += Math.max(1, transport.durationMs);
    resolvedContext = extractOutcome.context;

    if (requiredExtractBlocked || transport.status === "blocked_runtime" || transport.status === "blocked_invalid") {
      hardRuntimeBlocker = true;
      break;
    }
  }

  const ended = new Date();
  const runStatus = deriveRunStatusFromStepOutcomes({
    stepOutcomes: stepRows.map((row) => ({
      status: row.status as any,
      required: isStepRequired(contract.steps.find((step) => step.order === row.order && step.id === row.id)),
    })),
    hardRuntimeBlocker,
  });
  const executionResult: RegressionRunExecutionResult = {
    status: runStatus,
    preflight: preflightWithDiscovery.preflight,
    startedAt,
    endedAt: ended.toISOString(),
    steps: stepRows,
  };

  const correlationEvidence = buildPlanCorrelationEvidence({
    contract,
    resolvedContext,
    stepOutputsByOrder,
    stepEventTimesByOrder,
  });

  const artifacts = await writeRegressionRunArtifacts({
    workspaceRootAbs: args.workspaceRootAbs,
    ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
      ? { projectName: args.projectName.trim() }
      : {}),
    runId,
    ...(typeof args.executionProfileName === "string" ? { executionProfile: args.executionProfileName } : {}),
    ...(typeof args.suiteRunId === "string" ? { suiteRunId: args.suiteRunId } : {}),
    planRef: { name: args.planName, path: planRootAbs },
    resolvedContext,
    secretContextKeys: [
      ...new Set([
        ...contract.prerequisites.filter((entry) => entry.secret).map((entry) => entry.key),
        ...preflightWithDiscovery.secretContextKeys,
      ]),
    ],
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
      ...(correlationEvidence ?? {}),
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
