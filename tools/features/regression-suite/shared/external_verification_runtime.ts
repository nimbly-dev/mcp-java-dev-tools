import type {
  ExternalVerificationAssertionResult,
  ExternalVerificationExtractResult,
  NormalizedExternalVerificationResult,
  PlanExternalVerification,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type {
  RegressionExternalVerificationPhaseStatus,
  RegressionExecutionContinuation,
  RegressionRunStatus,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";
import type {
  TransportAdapter,
  TransportExecutionResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_transport.model";
import { validateNormalizedExternalVerificationResultShape } from "../../../spec/regression-execution-plan-spec/src/external_verification_contract.util";
import { executeSqlExternalVerification } from "./external_verification_sql_provider";
import { evaluateStepExpectations } from "../shared/regression_expectation_evaluator";
import { buildHttpPayload } from "../shared/regression_http_payload";
import { executeTransportWithRegistry } from "../shared/regression_transport_executor";
import { applyStepExtractWithDiagnostics } from "../../../spec/regression-execution-plan-spec/src/step_extract.util";
import { deepResolvePlaceholderValue } from "../../../spec/regression-execution-plan-spec/src/placeholder_resolution.util";

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function tryParseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function buildExtractedContext(args: {
  resolvedContext: Record<string, unknown>;
  extractMappings: PlanExternalVerification["extract"];
}): Record<string, unknown> | undefined {
  if (!args.extractMappings?.length) {
    return undefined;
  }
  const extractedContext: Record<string, unknown> = {};
  for (const mapping of args.extractMappings) {
    if (Object.prototype.hasOwnProperty.call(args.resolvedContext, mapping.as)) {
      extractedContext[mapping.as] = args.resolvedContext[mapping.as];
    }
  }
  return Object.keys(extractedContext).length > 0 ? extractedContext : undefined;
}

function normalizeExtractResults(
  extractResults: ReturnType<typeof applyStepExtractWithDiagnostics>["outcomes"],
  resolvedContext: Record<string, unknown>,
): ExternalVerificationExtractResult[] {
  return extractResults.map((entry) => ({
    from: entry.from,
    as: entry.as,
    required: entry.required,
    status: entry.status,
    ...(entry.status === "resolved" ? { value: resolvedContext[entry.as] } : {}),
    ...(entry.status === "unresolved" ? { reasonCode: "extract_path_missing" as const } : {}),
  }));
}

function normalizeAssertionResults(
  assertions: ReturnType<typeof evaluateStepExpectations>["assertions"],
): ExternalVerificationAssertionResult[] {
  return assertions.map((entry) => ({
    id: entry.id,
    actualPath: entry.actualPath,
    operator: entry.operator,
    status: entry.status === "blocked_invalid" ? "blocked" : entry.status,
    ...(typeof entry.expected === "undefined" ? {} : { expected: entry.expected }),
    ...(typeof entry.actual === "undefined" ? {} : { actual: entry.actual }),
    ...(typeof entry.message === "undefined" ? {} : { message: entry.message }),
    ...(typeof entry.reasonCode === "undefined" ? {} : { reasonCode: entry.reasonCode }),
  }));
}

function normalizeHttpVerificationResult(args: {
  verification: PlanExternalVerification;
  transport: TransportExecutionResult;
  resolvedContext: Record<string, unknown>;
}): NormalizedExternalVerificationResult {
  const responseBody = args.transport.bodyText ?? args.transport.bodyPreview ?? "";
  const envelope: Record<string, unknown> = {
    status: args.transport.status === "pass" ? "pass" : "fail",
    response: {
      statusCode: args.transport.statusCode ?? 0,
      body: responseBody,
      ...(args.transport.headers ? { headers: args.transport.headers } : {}),
      ...(typeof responseBody === "string" ? { bodyJson: tryParseJson(responseBody) } : {}),
      durationMs: args.transport.durationMs,
    },
  };

  const evaluation = evaluateStepExpectations({
    stepResult: envelope,
    expectations: args.verification.expect,
    transportFailure: args.transport.status === "fail_http",
    dependencyBlocked: args.transport.status === "blocked_invalid" || args.transport.status === "blocked_runtime",
  });
  const extractOutcome = applyStepExtractWithDiagnostics(
    envelope,
    args.verification.extract,
    args.resolvedContext,
  );
  const extractedContext = buildExtractedContext({
    resolvedContext: extractOutcome.context,
    extractMappings: args.verification.extract,
  });
  const extractResults = normalizeExtractResults(extractOutcome.outcomes, extractOutcome.context);
  const assertionResults = normalizeAssertionResults(evaluation.assertions);

  let reasonCode: string | undefined;
  let reasonMeta: Record<string, unknown> | undefined;

  if (args.transport.status === "blocked_invalid") {
    reasonCode = "external_verification_request_invalid";
    reasonMeta = {
      ...(args.transport.reasonCode ? { transportReasonCode: args.transport.reasonCode } : {}),
      ...(args.transport.reasonMeta ? args.transport.reasonMeta : {}),
    };
  } else if (args.transport.status === "blocked_runtime") {
    reasonCode = "external_verification_target_unreachable";
    reasonMeta = {
      ...(args.transport.reasonCode ? { transportReasonCode: args.transport.reasonCode } : {}),
      ...(args.transport.reasonMeta ? args.transport.reasonMeta : {}),
    };
  } else if (evaluation.status === "blocked_runtime") {
    reasonCode = "external_verification_response_invalid";
    reasonMeta = {
      assertionReasons: evaluation.assertions
        .filter((entry) => entry.status === "blocked_invalid")
        .map((entry) => ({
          id: entry.id,
          actualPath: entry.actualPath,
          reasonCode: entry.reasonCode,
        })),
    };
  } else if (evaluation.status === "fail_assertion") {
    reasonCode = "external_verification_expectation_failed";
  }

  if (extractOutcome.hasRequiredUnresolved) {
    reasonCode = "extract_path_missing";
    reasonMeta = {
      ...(reasonMeta ?? {}),
      extract: extractOutcome.outcomes.filter((entry) => entry.required && entry.status === "unresolved"),
    };
  }

  let status: NormalizedExternalVerificationResult["status"] = "pass";
  if (extractOutcome.hasRequiredUnresolved) {
    status = "blocked_runtime";
  } else if (evaluation.status === "blocked_runtime" || evaluation.status === "blocked_dependency") {
    status = "blocked_runtime";
  } else if (evaluation.status === "fail_assertion") {
    status = "fail_assertion";
  }

  const result: NormalizedExternalVerificationResult = {
    id: args.verification.id,
    providerType: "http",
    status,
    response: envelope.response as Record<string, unknown>,
    ...(extractResults.length > 0 ? { extractResults } : {}),
    ...(assertionResults.length > 0 ? { assertions: assertionResults } : {}),
    ...(extractedContext ? { extractedContext } : {}),
    ...(reasonCode ? { reasonCode } : {}),
    ...(reasonMeta && Object.keys(reasonMeta).length > 0 ? { reasonMeta } : {}),
  };

  const validation = validateNormalizedExternalVerificationResultShape(result);
  if (!validation.ok) {
    return {
      id: args.verification.id,
      providerType: "http",
      status: "blocked_runtime",
      response: {
        statusCode: args.transport.statusCode ?? 0,
        body: responseBody,
        ...(args.transport.headers ? { headers: args.transport.headers } : {}),
        durationMs: args.transport.durationMs,
      },
      reasonCode: "external_verification_response_invalid",
      reasonMeta: {
        validationReasonCode: validation.reasonCode,
      },
    };
  }

  return result;
}

function buildUnresolvedPlaceholderResult(args: {
  verification: PlanExternalVerification;
  error: Error;
}): NormalizedExternalVerificationResult {
  const message = args.error.message;
  const missingContextKey = message.startsWith("missing_context:") ? message.slice("missing_context:".length) : undefined;
  const invalidToken = message.startsWith("invalid_placeholder:") ? message.slice("invalid_placeholder:".length) : undefined;
  return {
    id: args.verification.id,
    providerType: "http",
    status: "blocked_runtime",
    response: {
      durationMs: 1,
    },
    ...(typeof missingContextKey === "string"
      ? {
          reasonCode: "external_verification_request_unresolved",
          reasonMeta: { missingContextKey },
        }
      : {}),
    ...(typeof invalidToken === "string"
      ? {
          reasonCode: "external_verification_request_invalid",
          reasonMeta: { invalidPlaceholderToken: invalidToken },
        }
      : {}),
    ...(typeof missingContextKey === "undefined" && typeof invalidToken === "undefined"
      ? {
          reasonCode: "external_verification_request_invalid",
          reasonMeta: { errorMessage: message },
        }
      : {}),
  };
}

function buildRuntimeFailureResult(args: {
  verification: PlanExternalVerification;
  error: Error;
}): NormalizedExternalVerificationResult {
  return {
    id: args.verification.id,
    providerType: "http",
    status: "blocked_runtime",
    response: {
      durationMs: 1,
    },
    reasonCode: "external_verification_target_unreachable",
    reasonMeta: {
      errorMessage: args.error.message,
    },
  };
}

export async function executeExternalVerifications(args: {
  externalVerification: PlanExternalVerification[] | undefined;
  resolvedContext: Record<string, unknown>;
  registry: Map<TransportAdapter["protocol"], TransportAdapter>;
  dependencyStatus: RegressionRunStatus;
  workspaceRootAbs: string;
  priorResults?: NormalizedExternalVerificationResult[];
  startVerificationIndex?: number;
  orchestrationDeadlineEpochMs?: number;
  nowMs?: () => number;
}): Promise<{
  phaseStatus: RegressionExternalVerificationPhaseStatus;
  results: NormalizedExternalVerificationResult[];
  resolvedContext: Record<string, unknown>;
  continuation?: RegressionExecutionContinuation;
}> {
  const nowMs = args.nowMs ?? (() => Date.now());
  const verifications = args.externalVerification ?? [];
  if (verifications.length === 0) {
    return {
      phaseStatus: "not_configured",
      results: [],
      resolvedContext: args.resolvedContext,
    };
  }
  if (args.dependencyStatus !== "pass") {
    return {
      phaseStatus: "skipped_dependency",
      results: [],
      resolvedContext: args.resolvedContext,
    };
  }

  let resolvedContext = { ...args.resolvedContext };
  const results: NormalizedExternalVerificationResult[] = [...(args.priorResults ?? [])];
  const startVerificationIndex =
    typeof args.startVerificationIndex === "number" &&
    Number.isInteger(args.startVerificationIndex) &&
    args.startVerificationIndex >= 0
      ? args.startVerificationIndex
      : 0;

  for (let verificationIndex = startVerificationIndex; verificationIndex < verifications.length; verificationIndex += 1) {
    const verification = verifications[verificationIndex];
    if (!verification) {
      continue;
    }
    if (
      typeof args.orchestrationDeadlineEpochMs === "number" &&
      nowMs() >= args.orchestrationDeadlineEpochMs
    ) {
      return {
        phaseStatus: "in_progress",
        results,
        resolvedContext,
        continuation: {
          phase: "external_verification",
          verificationIndex,
          phaseStartedAt: new Date(nowMs()).toISOString(),
        },
      };
    }
    if (verification.provider.type === "sql") {
      const sqlExecution = await executeSqlExternalVerification({
        verification,
        resolvedContext,
        workspaceRootAbs: args.workspaceRootAbs,
      });
      results.push(sqlExecution.result);
      if (sqlExecution.result.extractedContext) {
        resolvedContext = {
          ...resolvedContext,
          ...sqlExecution.result.extractedContext,
        };
      }
      continue;
    }

    if (verification.provider.type !== "http") {
      results.push({
        id: verification.id,
        providerType: verification.provider.type,
        status: "blocked_runtime",
        reasonCode: "external_verification_provider_not_supported",
      });
      continue;
    }

    const requestHttp = asRecord(verification.request.http) ?? {};
    let resolvedTransport: Record<string, unknown>;
    try {
      resolvedTransport = deepResolvePlaceholderValue(
        { http: requestHttp },
        resolvedContext,
      ) as Record<string, unknown>;
    } catch (error) {
      results.push(buildUnresolvedPlaceholderResult({
        verification,
        error: error instanceof Error ? error : new Error(String(error)),
      }));
      continue;
    }

    try {
      const payload = buildHttpPayload({
        resolvedTransport,
        context: resolvedContext,
      });
      const transport = await executeTransportWithRegistry({
        protocol: "http",
        payload,
        registry: args.registry,
      });
      const normalized = normalizeHttpVerificationResult({
        verification,
        transport,
        resolvedContext,
      });
      results.push(normalized);
      if (normalized.extractedContext) {
        resolvedContext = {
          ...resolvedContext,
          ...normalized.extractedContext,
        };
      }
    } catch (error) {
      results.push(buildRuntimeFailureResult({
        verification,
        error: error instanceof Error ? error : new Error(String(error)),
      }));
    }
  }

  let phaseStatus: RegressionExternalVerificationPhaseStatus = "pass";
  if (results.some((entry) => entry.status === "blocked_runtime")) {
    phaseStatus = "blocked";
  } else if (results.some((entry) => entry.status === "fail_assertion")) {
    phaseStatus = "fail";
  }

  return {
    phaseStatus,
    results,
    resolvedContext,
  };
}
