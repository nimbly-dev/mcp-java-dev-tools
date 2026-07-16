/**
 * Regression plan action support helpers for context resolution, correlation,
 * watcher/external-verification result normalization, and resume shapes.
 */
import { promises as fs } from "node:fs";

import type {
  PlanContract,
  PlanCorrelationPolicy,
  PlanStep,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
  WatcherExecutionEvidence,
  RegressionRunExecutionResult,
  RegressionRunStepResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import { normalizeHttpContextAliases } from "../shared/regression_http_request";
import { inferPlanApiBaseUrlFromProbeConfig } from "../shared/regression_plan_base_url";
import { readValueByPath } from "@tools-core/object_path_read";
import { combinePlanRunStatus } from "../shared/regression_watcher_runtime";

export function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

export function resolveTransportReasonMeta(transport: {
  reasonMeta?: Record<string, unknown>;
}): Record<string, unknown> | undefined {
  return transport.reasonMeta && Object.keys(transport.reasonMeta).length > 0
    ? transport.reasonMeta
    : undefined;
}

export function resolveProbeWaitFailure(args: {
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
        ...(asString(structured.reasonCode)
          ? { probeReasonCode: asString(structured.reasonCode) }
          : {}),
        ...(asString(structured.nextActionCode)
          ? { nextActionCode: asString(structured.nextActionCode) }
          : {}),
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
  asString,
  correlationJsonBodyCandidatePaths,
  type CorrelationKeyResolution,
} from "../support/plan_execution_conditions";

export async function readJsonFile<T>(absPath: string): Promise<T> {
  const text = await fs.readFile(absPath, "utf8");
  return JSON.parse(text) as T;
}

export async function resolvePlanExecutionContext(args: {
  workspaceRootAbs: string;
  planName: string;
  resolvedContext: Record<string, unknown>;
}): Promise<Record<string, unknown>> {
  const normalizedContext = normalizeHttpContextAliases(args.resolvedContext);
  if (
    typeof normalizedContext.apiBaseUrl === "string" &&
    normalizedContext.apiBaseUrl.trim().length > 0
  ) {
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

export function resolveCorrelationKeyValue(args: {
  correlation: PlanCorrelationPolicy;
  resolvedContext: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  stepContextsByOrder?: Map<number, Record<string, unknown>>;
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

  const availableOrders = Object.keys(args.stepOutputsByOrder)
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value))
    .sort((a, b) => b - a);
  const orders =
    typeof source.stepOrder === "number"
      ? availableOrders.filter((order) => order === source.stepOrder)
      : availableOrders;
  for (const order of orders) {
    const stepOutput = args.stepOutputsByOrder[order];
    if (!stepOutput) continue;
    if (source.type === "capture_field") {
      const stepContext = args.stepContextsByOrder?.get(order);
      const value = stepContext ? readValueByPath(stepContext, sourcePath) : undefined;
      const textValue = asString(value);
      if (textValue) {
        return {
          keyValue: textValue,
          sourceType: source.type,
          sourcePath,
          ...(typeof source.stepOrder === "number" ? { sourceStepOrder: source.stepOrder } : {}),
        };
      }
      continue;
    }
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
              ...(typeof source.stepOrder === "number"
                ? { sourceStepOrder: source.stepOrder }
                : {}),
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
              ...(typeof source.stepOrder === "number"
                ? { sourceStepOrder: source.stepOrder }
                : {}),
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
        ...(typeof source.stepOrder === "number" ? { sourceStepOrder: source.stepOrder } : {}),
      };
    }
  }

  return {
    sourceType: source.type,
    sourcePath,
    reasonCode: "correlation_key_extraction_failed",
  };
}

export function buildPlanCorrelationEvidence(args: {
  contract: PlanContract;
  resolvedContext: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  stepContextsByOrder?: Map<number, Record<string, unknown>>;
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
    ...(args.stepContextsByOrder ? { stepContextsByOrder: args.stepContextsByOrder } : {}),
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
    const strictLineKey =
      typeof target?.runtimeVerification?.strictProbeKey === "string"
        ? target.runtimeVerification.strictProbeKey
        : undefined;
    const expectation = strictLineKey
      ? correlation.strictLineExpectations?.find(
          (entry) =>
            entry.strictLineKey === strictLineKey &&
            (entry.stepOrder === undefined || entry.stepOrder === step.order),
        )
      : undefined;
    const probeEvidence = asRecord(stepOutput.probe);
    correlationEvents.push({
      eventId: `${step.id}:${step.order}`,
      probeId,
      timestampEpochMs,
      keyType: correlation.key.type,
      ...(keyValue ? { keyValue } : {}),
      ...(strictLineKey ? { lineKey: strictLineKey } : {}),
      ...(expectation
        ? {
            sequenceOrder: expectation.sequenceOrder,
            ...(typeof expectation.stepOrder === "number"
              ? { stepOrder: expectation.stepOrder }
              : {}),
            selectorPolicy: expectation.selectorPolicy,
            operator: expectation.operator,
            ...(typeof expectation.expectedHitDelta === "number"
              ? { expectedHitDelta: expectation.expectedHitDelta }
              : {}),
            ...(typeof expectation.expectedMinHitDelta === "number"
              ? { expectedMinHitDelta: expectation.expectedMinHitDelta }
              : {}),
            ...(typeof expectation.expectedMaxHitDelta === "number"
              ? { expectedMaxHitDelta: expectation.expectedMaxHitDelta }
              : {}),
          }
        : {}),
      ...(typeof probeEvidence?.runtimeInstanceId === "string"
        ? { runtimeInstanceId: probeEvidence.runtimeInstanceId }
        : {}),
      ...(typeof probeEvidence?.baselineHitCount === "number"
        ? { baselineHitCount: probeEvidence.baselineHitCount }
        : {}),
      ...(typeof probeEvidence?.currentHitCount === "number"
        ? { currentHitCount: probeEvidence.currentHitCount }
        : {}),
      eventType: step.protocol,
    });
  }

  return {
    correlationPolicy: {
      keyType: correlation.key.type,
      ...(keyValue ? { keyValue } : {}),
      ...(typeof keyResolution.sourceType === "string"
        ? { keySourceType: keyResolution.sourceType }
        : {}),
      ...(typeof keyResolution.sourcePath === "string"
        ? { keySourcePath: keyResolution.sourcePath }
        : {}),
      ...(typeof keyResolution.sourceStepOrder === "number"
        ? { keySourceStepOrder: keyResolution.sourceStepOrder }
        : {}),
      ...(typeof keyResolution.reasonCode === "string"
        ? { keyExtractionReasonCode: keyResolution.reasonCode }
        : {}),
      ...(typeof correlation.correlationSessionId === "string" &&
      correlation.correlationSessionId.trim().length > 0
        ? { correlationSessionId: correlation.correlationSessionId.trim() }
        : {}),
      maxWindowMs: correlation.window.maxWindowMs,
      ...(Array.isArray(correlation.expectedFlow)
        ? { expectedFlow: correlation.expectedFlow }
        : {}),
      ...(Array.isArray(correlation.strictLineExpectations)
        ? { strictLineExpectations: correlation.strictLineExpectations }
        : {}),
      ...(typeof correlation.window.startEpochMs === "number"
        ? { startEpochMs: correlation.window.startEpochMs }
        : {}),
      ...(typeof correlation.window.endEpochMs === "number"
        ? { endEpochMs: correlation.window.endEpochMs }
        : {}),
    },
    correlationEvents,
  };
}

export function isStepRequired(step: PlanStep | undefined): boolean {
  if (!step || !Array.isArray(step.expect) || step.expect.length === 0) {
    return true;
  }
  return step.expect.some((expectation) => expectation.required !== false);
}

export function combineRunStatusWithExternalVerification(args: {
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

export function collectRuntimeSecretContextKeys(
  resolvedContext: Record<string, unknown>,
): string[] {
  return Object.keys(resolvedContext)
    .filter((key) => key === "sql.connection" || key.startsWith("sql.connection."))
    .sort((a, b) => a.localeCompare(b));
}

export function cloneWatcherEvidence(value: unknown): WatcherExecutionEvidence[] {
  return Array.isArray(value)
    ? (value as WatcherExecutionEvidence[]).map((entry) => ({ ...entry }))
    : [];
}

export function cloneWatcherResults(
  value: RegressionRunExecutionResult["watchers"],
): NonNullable<RegressionRunExecutionResult["watchers"]> {
  return Array.isArray(value) ? value.map((entry) => ({ ...entry })) : [];
}

export function cloneExternalVerificationResults(
  value: RegressionRunExecutionResult["externalVerification"],
): NonNullable<RegressionRunExecutionResult["externalVerification"]> {
  return Array.isArray(value) ? value.map((entry) => ({ ...entry })) : [];
}

export function cloneStepRows(
  value: RegressionRunExecutionResult["steps"],
): RegressionRunStepResult[] {
  return Array.isArray(value) ? value.map((entry) => ({ ...entry })) : [];
}

export function buildResumeBlockedShape(reasonCode: string, requiredUserAction: string[]) {
  return {
    status: "blocked_invalid" as const,
    reasonCode,
    missing: [],
    checks: [],
    requiredUserAction,
  };
}
