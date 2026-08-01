import * as z from "zod/v4";

export const FAILURE_ANALYSIS_TOOL_CONTRACT = {
  name: "failure_analysis",
  description: "Analyze a pasted Java failure and verify bounded runtime reproduction evidence.",
} as const;

const SidecarBaseUrlSchema = z.string().url();
const SidecarAuthorizationSchema = z.string().trim().min(1).max(8_192).optional();
const TimeoutMsSchema = z.number().int().min(1_000).max(30_000).optional();
const InvestigationContextSchema = z
  .object({
    mode: z.enum(["guided", "hands_off"]),
    attemptLimit: z.number().int().min(1).max(10),
    elapsedTimeLimitMs: z.number().int().min(1_000).max(300_000),
  })
  .strict();
const ExpectedFingerprintSchema = z
  .object({
    exceptionType: z.string().trim().min(1),
    rootCauseType: z.string().trim().min(1),
    nearestApplicationMethodKey: z.string().trim().min(1),
  })
  .strict();

const TerminalOutcomeSchema = z.enum([
  "BLOCKED_AMBIGUOUS_JVM",
  "BLOCKED_MISSING_AUTH",
  "BLOCKED_MISSING_TRIGGER",
  "BLOCKED_USER_ACTION_REQUIRED",
  "BLOCKED_UNSAFE_OPERATION",
  "ENVIRONMENT_MISMATCH",
  "INCONCLUSIVE",
  "CANCELLED",
]);
const TerminalStateSchema = z
  .object({
    outcome: TerminalOutcomeSchema,
    reasonCode: z.string().trim().min(1).max(120),
    cleanupStatus: z.enum(["cleanup_confirmed", "cleanup_incomplete", "external_workflow_owned"]),
    attemptCount: z.number().int().min(0).max(10),
  })
  .strict();

const AnalyzeTraceInputSchema = z
  .object({
    trace: z.string().trim().min(1).max(200_000),
    sidecarBaseUrl: SidecarBaseUrlSchema,
    sidecarAuthorization: SidecarAuthorizationSchema,
    investigation: InvestigationContextSchema.optional(),
    timeoutMs: TimeoutMsSchema,
  })
  .strict();

const VerifyRuntimeInputSchema = z
  .object({
    captureId: z.string().trim().min(1),
    expectedFingerprint: ExpectedFingerprintSchema,
    lineHit: z
      .object({
        strictLineKey: z.string().trim().min(1),
        hitCount: z.number().int().positive(),
      })
      .strict(),
    sidecarBaseUrl: SidecarBaseUrlSchema,
    sidecarAuthorization: SidecarAuthorizationSchema,
    investigation: InvestigationContextSchema.optional(),
    timeoutMs: TimeoutMsSchema,
  })
  .strict();
const VerifyTerminalInputSchema = z
  .object({
    terminalState: TerminalStateSchema,
    investigation: InvestigationContextSchema.optional(),
  })
  .strict();
const VerifyReproductionInputSchema = z.union([
  VerifyRuntimeInputSchema,
  VerifyTerminalInputSchema,
]);

export const FailureAnalysisRequestSchema = z.discriminatedUnion("action", [
  z.object({ action: z.literal("analyze_trace"), input: AnalyzeTraceInputSchema }),
  z.object({ action: z.literal("verify_reproduction"), input: VerifyReproductionInputSchema }),
]);

export const FailureAnalysisInputSchema = {
  action: z.enum(["analyze_trace", "verify_reproduction"]),
  input: z.union([AnalyzeTraceInputSchema, VerifyReproductionInputSchema]),
} as const;

export type FailureAnalysisRequest = z.infer<typeof FailureAnalysisRequestSchema>;
export type FailureAnalysisAction = FailureAnalysisRequest["action"];
export type ExpectedFailureFingerprint = z.infer<typeof ExpectedFingerprintSchema>;
export type FailureInvestigationContext = z.infer<typeof InvestigationContextSchema>;
export type FailureTerminalState = z.infer<typeof TerminalStateSchema>;
