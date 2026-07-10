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
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
  WatcherExecutionEvidence,
  RegressionRunExecutionResult,
  RegressionRunStepResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { buildReplayPreflightWithDiscovery } from "../shared/regression_discovery_resolver";
import {
  applyStepExtractWithDiagnostics,
  buildTimestampRunId,
  resolvePrerequisiteContext,
  resolveStepTransport,
} from "../support/regression_plan_execution";
import { buildHttpPayload } from "../shared/regression_http_payload";
import { inferPlanApiBaseUrlFromProbeConfig } from "../shared/regression_plan_base_url";
import {
  deriveRunStatusFromStepOutcomes,
  evaluateStepExpectations,
} from "../shared/regression_expectation_evaluator";
import {
  combinePlanRunStatus,
  deriveWatcherPhaseStatus,
  executeWatchers,
} from "../shared/regression_watcher_runtime";
import {
  executeExternalVerifications,
} from "../shared/external_verification_runtime";
import {
  normalizeHttpContextAliases,
} from "../shared/regression_http_request";
import { readValueByPath } from "@tools-core/object_path_read";
import {
  createMcpWrappedTransportAdapter,
  createTransportRegistry,
  executeTransportWithRegistry,
} from "../shared/regression_transport_executor";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { writeRegressionRunArtifacts } from "../persistence/write_regression_run_artifacts";

type ConditionReasonCode =
  | "step_condition_malformed"
  | "step_condition_operator_invalid"
  | "step_condition_forward_reference"
  | "step_condition_path_missing"
  | "step_condition_type_mismatch";

import type {
  ExecuteRegressionPlanWorkflowArgs,
  ExecuteRegressionPlanWorkflowResult,
  RegressionMcpToolInvoker,
} from "../models/regression_suite.model";
export type { ExecuteRegressionPlanWorkflowArgs, ExecuteRegressionPlanWorkflowResult } from "../models/regression_suite.model";

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function resolveTransportReasonMeta(transport: { reasonMeta?: Record<string, unknown> }): Record<string, unknown> | undefined {
  return transport.reasonMeta && Object.keys(transport.reasonMeta).length > 0 ? transport.reasonMeta : undefined;
}

function resolveProbeWaitFailure(args: {
  structuredContent: Record<string, unknown> | null;
}): { reasonCode: "probe_wait_for_hit_failed"; reasonMeta: Record<string, unknown> } | null {
  const structured = args.structuredContent;
  if (!structured || "error" in structured) {
    return {
      reasonCode: "probe_wait_for_hit_failed",
      reasonMeta: {
        failedStep: "probe_wait_for_hit",
      },
    };
  }

  const status = asString(structured.status);
  const result = asRecord(structured.result);
  if (status && status !== "pass") {
    return {
      reasonCode: "probe_wait_for_hit_failed",
      reasonMeta: {
        failedStep: "probe_wait_for_hit",
        probeStatus: status,
        ...(asString(structured.reasonCode) ? { probeReasonCode: asString(structured.reasonCode) } : {}),
        ...(asString(structured.nextActionCode) ? { nextActionCode: asString(structured.nextActionCode) } : {}),
        ...(asString(structured.nextAction) ? { nextAction: asString(structured.nextAction) } : {}),
      },
    };
  }
  if (!result) {
    return {
      reasonCode: "probe_wait_for_hit_failed",
      reasonMeta: {
        failedStep: "probe_wait_for_hit",
        ...(status ? { probeStatus: status } : {}),
      },
    };
  }
  return null;
}

import {
  asPositiveInteger,
  asString,
  correlationJsonBodyCandidatePaths,
  evaluateStepCondition,
  resolveBlockedShape,
  tryParseJson,
  type CorrelationKeyResolution,
} from "../support/plan_execution_conditions";

async function readJsonFile<T>(absPath: string): Promise<T> {
  const text = await fs.readFile(absPath, "utf8");
  return JSON.parse(text) as T;
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

function combineRunStatusWithExternalVerification(args: {
  triggerStatus: RegressionRunExecutionResult["triggerStatus"];
  watcherStatus: RegressionRunExecutionResult["watcherStatus"];
  externalVerificationStatus: RegressionRunExecutionResult["externalVerificationStatus"];
}): "pass" | "fail" | "blocked" | "in_progress" {
  const baseStatus = combinePlanRunStatus({
    triggerStatus: args.triggerStatus ?? "pass",
    watcherStatus: args.watcherStatus ?? "not_configured",
  });
  if (baseStatus === "in_progress" || baseStatus === "blocked" || baseStatus === "fail") {
    return baseStatus;
  }
  if (args.externalVerificationStatus === "in_progress") {
    return "in_progress";
  }
  if (args.externalVerificationStatus === "blocked") {
    return "blocked";
  }
  if (args.externalVerificationStatus === "fail") {
    return "fail";
  }
  return "pass";
}

function collectRuntimeSecretContextKeys(resolvedContext: Record<string, unknown>): string[] {
  return Object.keys(resolvedContext)
    .filter((key) => key === "sql.connection" || key.startsWith("sql.connection."))
    .sort((a, b) => a.localeCompare(b));
}

function cloneWatcherEvidence(value: unknown): WatcherExecutionEvidence[] {
  return Array.isArray(value) ? (value as WatcherExecutionEvidence[]).map((entry) => ({ ...entry })) : [];
}

function cloneWatcherResults(value: RegressionRunExecutionResult["watchers"]): NonNullable<RegressionRunExecutionResult["watchers"]> {
  return Array.isArray(value) ? value.map((entry) => ({ ...entry })) : [];
}

function cloneExternalVerificationResults(
  value: RegressionRunExecutionResult["externalVerification"],
): NonNullable<RegressionRunExecutionResult["externalVerification"]> {
  return Array.isArray(value) ? value.map((entry) => ({ ...entry })) : [];
}

function cloneStepRows(value: RegressionRunExecutionResult["steps"]): RegressionRunStepResult[] {
  return Array.isArray(value) ? value.map((entry) => ({ ...entry })) : [];
}

function buildResumeBlockedShape(reasonCode: string, requiredUserAction: string[]) {
  return {
    status: "blocked_invalid" as const,
    reasonCode,
    missing: [],
    checks: [],
    requiredUserAction,
  };
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
  const runId =
    typeof args.runId === "string" && args.runId.trim().length > 0
      ? args.runId.trim()
      : buildTimestampRunId(now, 1);
  const startedAt = now.toISOString();
  const orchestrationDeadlineEpochMs =
    typeof args.orchestrationTimeoutBudgetMs === "number" && args.orchestrationTimeoutBudgetMs > 0
      ? now.getTime() + args.orchestrationTimeoutBudgetMs
      : undefined;
  const resumeExecutionResult = args.resumeState?.executionResult;
  const resumeContinuation = resumeExecutionResult?.continuation;
  const isResumedInProgress =
    resumeExecutionResult?.status === "in_progress" &&
    typeof resumeContinuation !== "undefined";

  const resolvedContextInitial = isResumedInProgress && args.resumeState
    ? normalizeHttpContextAliases({ ...args.resumeState.resolvedContext })
    : normalizeHttpContextAliases({
        ...preflightWithDiscovery.resolvedContext,
        ...resolvePrerequisiteContext(
          contract.prerequisites,
          preflightWithDiscovery.resolvedContext,
        ),
      });

  const adapter = createMcpWrappedTransportAdapter(args.mcpInvoke);
  const registry = createTransportRegistry([adapter]);

  let resolvedContext = isResumedInProgress
    ? { ...resolvedContextInitial }
    : await resolvePlanExecutionContext({
        workspaceRootAbs: args.workspaceRootAbs,
        planName: args.planName,
        resolvedContext: { ...resolvedContextInitial },
      });
  const stepRows: RegressionRunStepResult[] = isResumedInProgress && resumeExecutionResult
    ? cloneStepRows(resumeExecutionResult.steps)
    : [];
  const stepOutputsByOrder: Record<number, Record<string, unknown>> = {};
  const stepEventTimesByOrder: Record<number, number> = {};
  const stepContextsByOrder = new Map<number, Record<string, unknown>>();
  let hardRuntimeBlocker = resumeExecutionResult?.triggerStatus === "blocked";
  let eventCursorEpochMs = now.getTime();
  if (!isResumedInProgress) {
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
    const strictProbeWaitForHit = target?.runtimeVerification?.waitForHit;
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
        ? buildHttpPayload({ resolvedTransport, context: resolvedContext })
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
        maxRetries: asPositiveInteger(strictProbeWaitForHit?.maxRetries) ?? 5,
        pollIntervalMs: asPositiveInteger(strictProbeWaitForHit?.pollIntervalMs) ?? 300,
      };
      const waitTimeoutMs = asPositiveInteger(strictProbeWaitForHit?.timeoutMs);
      if (typeof waitTimeoutMs === "number") {
        waitIn.timeoutMs = waitTimeoutMs;
      }
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
      const waitFailure = resolveProbeWaitFailure({ structuredContent: waitStructured });
      if (waitFailure) {
        hardRuntimeBlocker = true;
        stepRows.push({
          order: step.order,
          id: step.id,
          status: "blocked_runtime",
          durationMs: transport.durationMs,
          statusCode: transport.statusCode ?? 0,
          assertions: [],
          reasonCode: waitFailure.reasonCode,
          reasonMeta: waitFailure.reasonMeta,
          ...(typeof step.when === "undefined"
            ? {}
            : {
                conditionEvaluation: {
                  status: true as const,
                },
              }),
        });
        break;
      }
      const waitResult = asRecord(waitStructured?.result);
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
    stepContextsByOrder.set(step.order, { ...resolvedContext });

    if (requiredExtractBlocked || transport.status === "blocked_runtime" || transport.status === "blocked_invalid") {
      hardRuntimeBlocker = true;
      break;
    }
    }
  } else if (!resumeExecutionResult?.triggerStatus) {
    return {
      status: "blocked",
      preflight: buildResumeBlockedShape("plan_resume_invalid", [
        `Persist triggerStatus before resuming regression plan '${args.planName}'.`,
      ]),
    };
  }

  const ended = new Date();
  const triggerStatus = isResumedInProgress && resumeExecutionResult?.triggerStatus
    ? resumeExecutionResult.triggerStatus
    : deriveRunStatusFromStepOutcomes({
        stepOutcomes: stepRows.map((row) => ({
          status: row.status as any,
          required: isStepRequired(contract.steps.find((step) => step.order === row.order && step.id === row.id)),
        })),
        hardRuntimeBlocker,
      });
  const watcherExecution = await executeWatchers({
    contract,
    resolvedContext,
    registry,
    stepRows,
    stepContextsByOrder,
    ...(resumeContinuation?.phase === "watchers" && resumeExecutionResult
      ? {
          priorWatcherRows: cloneWatcherResults(resumeExecutionResult.watchers),
          priorWatcherEvidence: cloneWatcherEvidence(args.resumeState?.evidence?.watcherExecutions),
          startWatcherIndex: resumeContinuation.watcherIndex,
          currentWatcherStartedAt: resumeContinuation.phaseStartedAt,
        }
      : {}),
    ...(typeof orchestrationDeadlineEpochMs === "number" ? { orchestrationDeadlineEpochMs } : {}),
  });
  const watcherRows = watcherExecution.watcherRows;
  const watcherEvidence = watcherExecution.watcherEvidence;
  const watcherStatus = watcherExecution.phaseStatus;
  const externalVerification = watcherStatus === "in_progress"
    ? {
        phaseStatus: resumeExecutionResult?.externalVerificationStatus,
        results: cloneExternalVerificationResults(resumeExecutionResult?.externalVerification),
        resolvedContext,
        continuation: undefined,
      }
    : await executeExternalVerifications({
        externalVerification: contract.externalVerification,
        resolvedContext,
        registry,
        dependencyStatus: triggerStatus,
        workspaceRootAbs: args.workspaceRootAbs,
        ...(resumeContinuation?.phase === "external_verification" && resumeExecutionResult
          ? {
              priorResults: cloneExternalVerificationResults(resumeExecutionResult.externalVerification),
              startVerificationIndex: resumeContinuation.verificationIndex,
            }
          : {}),
        ...(typeof orchestrationDeadlineEpochMs === "number" ? { orchestrationDeadlineEpochMs } : {}),
      });
  resolvedContext = externalVerification.resolvedContext;
  const runStatus =
    watcherStatus === "in_progress" || externalVerification.phaseStatus === "in_progress"
      ? "in_progress"
      : combineRunStatusWithExternalVerification({
          triggerStatus,
          watcherStatus,
          externalVerificationStatus: externalVerification.phaseStatus,
        });
  const executionResult: RegressionRunExecutionResult = {
    status: runStatus,
    triggerStatus,
    watcherStatus,
    ...(typeof externalVerification.phaseStatus === "undefined"
      ? {}
      : { externalVerificationStatus: externalVerification.phaseStatus }),
    ...(watcherExecution.continuation ? { continuation: watcherExecution.continuation } : {}),
    ...(externalVerification.continuation ? { continuation: externalVerification.continuation } : {}),
    preflight: preflightWithDiscovery.preflight,
    startedAt: resumeExecutionResult?.startedAt ?? startedAt,
    endedAt: ended.toISOString(),
    steps: stepRows,
    ...(watcherRows.length > 0 ? { watchers: watcherRows } : {}),
    ...(externalVerification.results.length > 0 ? { externalVerification: externalVerification.results } : {}),
  };

  const correlationEvidence =
    Object.keys(stepOutputsByOrder).length > 0
      ? buildPlanCorrelationEvidence({
          contract,
          resolvedContext,
          stepOutputsByOrder,
          stepEventTimesByOrder,
        })
      : (args.resumeState?.evidence
          ? {
              ...(args.resumeState.evidence.correlationPolicy ? { correlationPolicy: args.resumeState.evidence.correlationPolicy } : {}),
              ...(args.resumeState.evidence.correlationEvents ? { correlationEvents: args.resumeState.evidence.correlationEvents } : {}),
            }
          : undefined);

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
        ...collectRuntimeSecretContextKeys(resolvedContext),
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
      ...(watcherEvidence.length > 0 ? { watcherExecutions: watcherEvidence } : {}),
      ...(externalVerification.results.length > 0
        ? { externalVerificationExecutions: externalVerification.results }
        : {}),
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
