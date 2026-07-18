import path from "node:path";

import type {
  PlanContract,
  PlanMetadata,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
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
import {
  deriveRunStatusFromStepOutcomes,
  evaluateStepExpectations,
} from "../shared/regression_expectation_evaluator";
import { executeWatchers } from "../shared/regression_watcher_runtime";
import { executeExternalVerifications } from "../shared/external_verification_runtime";
import { normalizeHttpContextAliases } from "../shared/regression_http_request";
import {
  createMcpWrappedTransportAdapter,
  createTransportRegistry,
  executeTransportWithRegistry,
} from "../shared/regression_transport_executor";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import {
  toCorrelationArtifactFromEvidence,
  writeRegressionRunArtifacts,
} from "../persistence/regression_run_artifact_writer";
import { readRuntimeCorrelationEvents } from "../support/regression_runtime_correlation_events";
import { openRunStateStore, readRuntimeEvidenceCursor } from "@tools-feature-artifact-management";
import { loadProbeRegistry } from "@tools-core/probe-registry";
import { fetchJson } from "@tools-core/http";

import type {
  ExecuteRegressionPlanWorkflowArgs,
  ExecuteRegressionPlanWorkflowResult,
} from "../models/regression_suite.model";
export type {
  ExecuteRegressionPlanWorkflowArgs,
  ExecuteRegressionPlanWorkflowResult,
} from "../models/regression_suite.model";

import {
  asRecord,
  resolveTransportReasonMeta,
  resolveProbeWaitFailure,
  readJsonFile,
  resolvePlanExecutionContext,
  buildPlanCorrelationEvidence,
  isStepRequired,
  combineRunStatusWithExternalVerification,
  collectRuntimeSecretContextKeys,
  cloneWatcherEvidence,
  cloneWatcherResults,
  cloneExternalVerificationResults,
  cloneStepRows,
  buildResumeBlockedShape,
} from "../support/regression_plan_action_support";
import {
  asPositiveInteger,
  evaluateStepCondition,
  resolveBlockedShape,
  tryParseJson,
} from "../support/plan_execution_conditions";
export async function executeRegressionPlanWorkflow(
  args: ExecuteRegressionPlanWorkflowArgs,
): Promise<ExecuteRegressionPlanWorkflowResult> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const planRootAbs = path.join(plansRootAbs, args.planName);
  const metadata = await readJsonFile<PlanMetadata>(path.join(planRootAbs, "metadata.json"));
  const contract = await readJsonFile<PlanContract>(path.join(planRootAbs, "contract.json"));

  const projectName = path.basename(path.dirname(path.dirname(plansRootAbs)));
  const projectsFileAbs = path.join(args.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
  let activeProbeRegistry: ReturnType<typeof loadProbeRegistry> | undefined;
  let probeRegistryAvailable = true;
  try {
    activeProbeRegistry = loadProbeRegistry({
      filePath: path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json"),
      workspaceRootAbs: args.workspaceRootAbs,
    });
  } catch {
    probeRegistryAvailable = false;
  }
  let availableProbeIds: string[] | undefined;
  if (activeProbeRegistry) {
    availableProbeIds = Array.from(activeProbeRegistry.probesById.keys());
  }

  const preflightWithDiscovery = await buildReplayPreflightWithDiscovery({
    metadata,
    contract,
    providedContext: args.providedContext ?? {},
    targetCandidateCount: 1,
    ...(availableProbeIds ? { availableProbeIds } : {}),
    probeRegistryAvailable,
    adapters: {},
    projectContextOptions: {
      workspaceRootAbs: args.workspaceRootAbs,
      projectsFileAbs,
      env: process.env,
      ...(typeof args.runtimeContextName === "string"
        ? { runtimeContextName: args.runtimeContextName }
        : {}),
      ...(typeof args.executionProfileName === "string"
        ? { executionProfileName: args.executionProfileName }
        : {}),
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
    resumeExecutionResult?.status === "in_progress" && typeof resumeContinuation !== "undefined";

  const resolvedContextInitial =
    isResumedInProgress && args.resumeState
      ? normalizeHttpContextAliases({
          ...args.resumeState.resolvedContext,
          ...preflightWithDiscovery.resolvedContext,
          ...resolvePrerequisiteContext(
            contract.prerequisites,
            preflightWithDiscovery.resolvedContext,
          ),
        })
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
  const stepRows: RegressionRunStepResult[] =
    isResumedInProgress && resumeExecutionResult ? cloneStepRows(resumeExecutionResult.steps) : [];
  const stepOutputsByOrder: Record<number, Record<string, unknown>> = {};
  const stepEventTimesByOrder: Record<number, number> = {};
  const stepContextsByOrder = new Map<number, Record<string, unknown>>();
  const suiteContext: Record<string, unknown> = {};
  if (isResumedInProgress && resumeExecutionResult && args.resumeState) {
    const persistedContext = args.resumeState.resolvedContext;
    for (const step of contract.steps) {
      const persistedStep = resumeExecutionResult.steps.find(
        (entry) => entry.order === step.order && entry.id === step.id && entry.status === "pass",
      );
      if (!persistedStep || !Array.isArray(persistedStep.extract)) continue;
      for (const extract of step.extract ?? []) {
        if (
          extract.scope !== "suite" ||
          !persistedStep.extract.some(
            (entry) => entry.as === extract.as && entry.status === "resolved",
          ) ||
          !Object.prototype.hasOwnProperty.call(persistedContext, extract.as)
        ) {
          continue;
        }
        suiteContext[extract.as] = persistedContext[extract.as];
      }
    }
  }
  let hardRuntimeBlocker = resumeExecutionResult?.triggerStatus === "blocked";
  let eventCursorEpochMs = now.getTime();
  const runtimeCorrelationAfterSequenceByProbe = new Map<string, number>();
  let runtimeCorrelationCursorAvailable = false;
  const runtimeEvidence = contract.correlation?.runtimeEvidence;
  const runtimeCorrelationLeaseTtlMs = Math.min(
    300_000,
    Math.max(5_000, (runtimeEvidence?.maxDurationMs ?? 30_000) + 60_000),
  );
  const runtimeCorrelationEventKeyPath = runtimeEvidence?.eventKeyPath?.trim() ?? "";
  let runtimeCorrelationLeaseLost = false;
  let runtimeCorrelationLeaseRenewal: ReturnType<typeof setInterval> | undefined;
  const correlationSessionId =
    typeof contract.correlation?.correlationSessionId === "string"
      ? contract.correlation.correlationSessionId.trim()
      : "";
  const runtimeProbeTargets: Array<{ probeId: string; baseUrl: string }> = [];
  let probeBaseUrl =
    typeof resolvedContext.probeBaseUrl === "string" ? resolvedContext.probeBaseUrl.trim() : "";
  const configuredConsumerProbeIds = runtimeEvidence?.probeIds ?? [];
  if (configuredConsumerProbeIds.length > 0) {
    try {
      const registry =
        activeProbeRegistry ??
        loadProbeRegistry({
          filePath: path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json"),
          workspaceRootAbs: args.workspaceRootAbs,
        });
      for (const probeId of configuredConsumerProbeIds) {
        const configuredProbe = registry.probesById.get(probeId);
        if (configuredProbe?.baseUrl)
          runtimeProbeTargets.push({ probeId, baseUrl: configuredProbe.baseUrl.trim() });
      }
      probeBaseUrl = runtimeProbeTargets[0]?.baseUrl ?? probeBaseUrl;
    } catch {
      // Preserve the resolved context fallback; retrieval will fail closed if it is unavailable.
    }
  }
  const releaseRuntimeCorrelationLeases = async (): Promise<void> => {
    if (runtimeEvidence?.required !== true || runtimeProbeTargets.length === 0) return;
    await Promise.all(
      runtimeProbeTargets.map(async (target) => {
        try {
          await fetchJson(new URL("/__probe/correlation/configure", target.baseUrl).toString(), {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ executionId: runId, release: true }),
            timeoutMs: 5_000,
          });
        } catch {
          // Lease expiry remains the fail-safe if the Sidecar is unreachable.
        }
      }),
    );
  };
  if (runtimeProbeTargets.length === 0 && probeBaseUrl)
    runtimeProbeTargets.push({
      probeId: configuredConsumerProbeIds[0] ?? "runtime",
      baseUrl: probeBaseUrl,
    });
  try {
    if (
      runtimeEvidence?.required === true &&
      correlationSessionId &&
      runtimeProbeTargets.length > 0
    ) {
      try {
        if (!runtimeCorrelationEventKeyPath) throw new Error("correlation_event_key_path_missing");
        for (const target of runtimeProbeTargets) {
          const configured = await fetchJson(
            new URL("/__probe/correlation/configure", target.baseUrl).toString(),
            {
              method: "POST",
              headers: { "content-type": "application/json" },
              body: JSON.stringify({
                sessionId: correlationSessionId,
                executionId: runId,
                eventKeyPath: runtimeCorrelationEventKeyPath,
                leaseTtlMs: runtimeCorrelationLeaseTtlMs,
              }),
              timeoutMs: 5_000,
            },
          );
          if (
            configured.status < 200 ||
            configured.status >= 300 ||
            configured.json?.configured !== true
          )
            throw new Error("correlation_runtime_configure_failed");
        }
        runtimeCorrelationLeaseRenewal = setInterval(
          () => {
            void (async () => {
              for (const target of runtimeProbeTargets) {
                try {
                  const renewed = await fetchJson(
                    new URL("/__probe/correlation/configure", target.baseUrl).toString(),
                    {
                      method: "POST",
                      headers: { "content-type": "application/json" },
                      body: JSON.stringify({
                        sessionId: correlationSessionId,
                        executionId: runId,
                        eventKeyPath: runtimeCorrelationEventKeyPath,
                        leaseTtlMs: runtimeCorrelationLeaseTtlMs,
                      }),
                      timeoutMs: 5_000,
                    },
                  );
                  if (
                    renewed.status < 200 ||
                    renewed.status >= 300 ||
                    renewed.json?.configured !== true
                  ) {
                    runtimeCorrelationLeaseLost = true;
                  }
                } catch {
                  runtimeCorrelationLeaseLost = true;
                }
              }
            })();
          },
          Math.max(1_000, Math.floor(runtimeCorrelationLeaseTtlMs / 3)),
        );
        const projectStore = await openRunStateStore({
          workspaceRootAbs: args.workspaceRootAbs,
          projectName,
        });
        if (projectStore.ok) {
          try {
            const configuredRuntimeInstanceId =
              runtimeEvidence.runtimeInstanceIds?.length === 1
                ? runtimeEvidence.runtimeInstanceIds[0]
                : undefined;
            for (const target of runtimeProbeTargets) {
              const candidateCursor = readRuntimeEvidenceCursor({
                store: projectStore,
                projectName,
                runId,
                correlationSessionId,
                probeId: target.probeId,
                ...(configuredRuntimeInstanceId
                  ? { runtimeInstanceId: configuredRuntimeInstanceId }
                  : {}),
              });
              if (candidateCursor?.status === "pending_artifact") continue;
              const baseline = await readRuntimeCorrelationEvents({
                baseUrl: target.baseUrl,
                sessionId: correlationSessionId,
                afterSequence: candidateCursor?.lastSequence ?? 0,
                limit: 1,
                maxEvents: 1,
              });
              if (
                candidateCursor &&
                baseline.streamRuntimeInstanceId &&
                baseline.streamRuntimeInstanceId !== candidateCursor.streamRuntimeInstanceId
              ) {
                runtimeCorrelationCursorAvailable = false;
                continue;
              }
              if (!baseline.contractValid) {
                runtimeCorrelationCursorAvailable = false;
                continue;
              }
              runtimeCorrelationAfterSequenceByProbe.set(
                target.probeId,
                candidateCursor?.lastSequence ?? baseline.highWaterSequence,
              );
            }
          } finally {
            projectStore.close();
          }
        }
        if (runtimeCorrelationAfterSequenceByProbe.size === runtimeProbeTargets.length)
          runtimeCorrelationCursorAvailable = true;
      } catch {
        runtimeCorrelationCursorAvailable = false;
      }
    }
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
        const correlationEnabled = contract.correlation?.enabled === true;
        const strictLineCorrelationEnabled =
          correlationEnabled &&
          typeof strictProbeKey === "string" &&
          contract.correlation?.strictLineExpectations?.some(
            (expectation) => expectation.strictLineKey === strictProbeKey,
          ) === true;
        const strictProbeEnabled =
          metadata.execution.probeVerification === true &&
          typeof strictProbeKey === "string" &&
          strictProbeKey.trim().length > 0;
        let baselineHitCount: number | undefined;
        let runtimeInstanceId: string | undefined;

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
          if (strictLineCorrelationEnabled) {
            const baselineOut = await args.mcpInvoke({
              toolName: "probe",
              input: { action: "status", input: resetIn },
            });
            const baselineStructured = asRecord(baselineOut.structuredContent);
            const baselineResponse = asRecord(baselineStructured?.response);
            const baselineJson = asRecord(baselineResponse?.json);
            const baselineRuntime = asRecord(baselineJson?.runtime);
            if (
              typeof baselineJson?.hitCount !== "number" ||
              typeof baselineRuntime?.sessionId !== "string" ||
              !baselineRuntime.sessionId.trim()
            ) {
              hardRuntimeBlocker = true;
              stepRows.push({
                order: step.order,
                id: step.id,
                status: "blocked_runtime",
                durationMs: 1,
                statusCode: 0,
                assertions: [],
                reasonCode: "correlation_runtime_instance_missing",
              });
              break;
            }
            baselineHitCount = baselineJson.hitCount;
            runtimeInstanceId = baselineRuntime.sessionId;
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
          let currentHitCount: number | undefined;
          if (strictLineCorrelationEnabled) {
            const finalOut = await args.mcpInvoke({
              toolName: "probe",
              input: { action: "status", input: waitIn },
            });
            const finalStructured = asRecord(finalOut.structuredContent);
            const finalResponse = asRecord(finalStructured?.response);
            const finalJson = asRecord(finalResponse?.json);
            const finalRuntime = asRecord(finalJson?.runtime);
            if (
              typeof finalJson?.hitCount !== "number" ||
              typeof finalRuntime?.sessionId !== "string" ||
              finalRuntime.sessionId !== runtimeInstanceId
            ) {
              hardRuntimeBlocker = true;
              stepRows.push({
                order: step.order,
                id: step.id,
                status: "blocked_runtime",
                durationMs: transport.durationMs,
                statusCode: transport.statusCode ?? 0,
                assertions: [],
                reasonCode: "correlation_runtime_instance_changed",
              });
              break;
            }
            currentHitCount = finalJson.hitCount;
          }
          stepEnvelope.probe = {
            hit,
            key: strictProbeKey,
            ...(typeof targetProbeId === "string" ? { probeId: targetProbeId } : {}),
            ...(typeof baselineHitCount === "number" ? { baselineHitCount } : {}),
            ...(typeof currentHitCount === "number" ? { currentHitCount } : {}),
            ...(runtimeInstanceId ? { runtimeInstanceId } : {}),
            coverage: hit ? "verified_line_hit" : "http_only_unverified_line",
          };
        }
        const evalResult = evaluateStepExpectations({
          stepResult: stepEnvelope,
          expectations: step.expect,
          transportFailure: transport.status === "fail_http",
          dependencyBlocked:
            transport.status === "blocked_invalid" || transport.status === "blocked_runtime",
        });
        const transportReasonMeta = resolveTransportReasonMeta(transport);
        const extractOutcome = applyStepExtractWithDiagnostics(
          stepEnvelope,
          step.extract,
          resolvedContext,
        );
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
        if (stepStatus === "pass") {
          for (const extract of step.extract ?? []) {
            if (
              extract.scope !== "suite" ||
              !Object.prototype.hasOwnProperty.call(extractOutcome.context, extract.as)
            ) {
              continue;
            }
            suiteContext[extract.as] = extractOutcome.context[extract.as];
          }
        }
        stepOutputsByOrder[step.order] = stepEnvelope;
        stepEventTimesByOrder[step.order] = eventCursorEpochMs;
        eventCursorEpochMs += Math.max(1, transport.durationMs);
        resolvedContext = extractOutcome.context;
        stepContextsByOrder.set(step.order, { ...resolvedContext });

        if (
          requiredExtractBlocked ||
          transport.status === "blocked_runtime" ||
          transport.status === "blocked_invalid"
        ) {
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
    const triggerStatus =
      isResumedInProgress && resumeExecutionResult?.triggerStatus
        ? resumeExecutionResult.triggerStatus
        : deriveRunStatusFromStepOutcomes({
            stepOutcomes: stepRows.map((row) => ({
              status: row.status as any,
              required: isStepRequired(
                contract.steps.find((step) => step.order === row.order && step.id === row.id),
              ),
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
            priorWatcherEvidence: cloneWatcherEvidence(
              args.resumeState?.evidence?.watcherExecutions,
            ),
            startWatcherIndex: resumeContinuation.watcherIndex,
            currentWatcherStartedAt: resumeContinuation.phaseStartedAt,
            continuation: resumeContinuation,
          }
        : {}),
      ...(typeof orchestrationDeadlineEpochMs === "number" ? { orchestrationDeadlineEpochMs } : {}),
      ...(args.renewSuiteLease ? { renewSuiteLease: args.renewSuiteLease } : {}),
    });
    const watcherRows = [
      ...new Map(watcherExecution.watcherRows.map((row) => [row.id, row])).values(),
    ];
    const watcherEvidence = [
      ...new Map(watcherExecution.watcherEvidence.map((entry) => [entry.id, entry])).values(),
    ];
    const watcherStatus = watcherExecution.phaseStatus;
    const externalVerification =
      watcherStatus === "in_progress"
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
                  priorResults: cloneExternalVerificationResults(
                    resumeExecutionResult.externalVerification,
                  ),
                  startVerificationIndex: resumeContinuation.verificationIndex,
                }
              : {}),
            ...(typeof orchestrationDeadlineEpochMs === "number"
              ? { orchestrationDeadlineEpochMs }
              : {}),
          });
    resolvedContext = externalVerification.resolvedContext;
    let runStatus =
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
      ...(externalVerification.continuation
        ? { continuation: externalVerification.continuation }
        : {}),
      preflight: preflightWithDiscovery.preflight,
      startedAt: resumeExecutionResult?.startedAt ?? startedAt,
      endedAt: ended.toISOString(),
      steps: stepRows,
      ...(watcherRows.length > 0 ? { watchers: watcherRows } : {}),
      ...(externalVerification.results.length > 0
        ? { externalVerification: externalVerification.results }
        : {}),
    };

    let correlationEvidence: Record<string, unknown> | undefined;
    correlationEvidence =
      Object.keys(stepOutputsByOrder).length > 0
        ? await (async () => {
            let runtimeCorrelationEvents: Array<Record<string, unknown>> = [];
            if (
              runtimeEvidence?.required === true &&
              correlationSessionId &&
              probeBaseUrl &&
              runtimeCorrelationCursorAvailable &&
              !runtimeCorrelationLeaseLost
            ) {
              try {
                for (const target of runtimeProbeTargets) {
                  const cursor = await readRuntimeCorrelationEvents({
                    baseUrl: target.baseUrl,
                    sessionId: correlationSessionId,
                    afterSequence: runtimeCorrelationAfterSequenceByProbe.get(target.probeId) ?? 0,
                    limit: runtimeEvidence.pageLimit ?? 256,
                    maxEvents: runtimeEvidence.maxEvents ?? 10_000,
                    maxBytes: runtimeEvidence.maxBytes ?? 1_048_576,
                    maxDurationMs: runtimeEvidence.maxDurationMs ?? 30_000,
                  });
                  if (cursor.budgetExceeded || !cursor.contractValid) {
                    runtimeCorrelationEvents = [];
                    break;
                  }
                  runtimeCorrelationEvents.push(...cursor.events);
                }
              } catch {
                runtimeCorrelationEvents = [];
              }
            }
            return buildPlanCorrelationEvidence({
              contract,
              resolvedContext,
              stepOutputsByOrder,
              stepContextsByOrder,
              stepEventTimesByOrder,
              runtimeCorrelationEvents,
              runtimeExecutionId: runId,
            });
          })()
        : args.resumeState?.evidence
          ? {
              ...(args.resumeState.evidence.correlationPolicy
                ? { correlationPolicy: args.resumeState.evidence.correlationPolicy }
                : {}),
              ...(args.resumeState.evidence.correlationEvents
                ? { correlationEvents: args.resumeState.evidence.correlationEvents }
                : {}),
            }
          : undefined;

    const correlationArtifact = correlationEvidence
      ? toCorrelationArtifactFromEvidence({
          evidence: correlationEvidence,
          resolvedContext,
          now: ended,
        })
      : undefined;
    const expectedFlowViolation =
      correlationArtifact?.status === "fail_closed" &&
      (correlationArtifact.reasonCode === "missing_expected_flow_event" ||
        correlationArtifact.reasonCode === "flow_expectation_mismatch");
    if (runStatus === "pass" && expectedFlowViolation) {
      runStatus = "fail";
      executionResult.status = runStatus;
    }

    const artifacts = await writeRegressionRunArtifacts({
      workspaceRootAbs: args.workspaceRootAbs,
      ...(typeof args.projectName === "string" && args.projectName.trim().length > 0
        ? { projectName: args.projectName.trim() }
        : {}),
      runId,
      ...(typeof args.executionProfileName === "string"
        ? { executionProfile: args.executionProfileName }
        : {}),
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
      ...(Object.keys(suiteContext).length > 0 ? { suiteContext } : {}),
    };
  } finally {
    if (runtimeCorrelationLeaseRenewal) clearInterval(runtimeCorrelationLeaseRenewal);
    await releaseRuntimeCorrelationLeases();
  }
}
