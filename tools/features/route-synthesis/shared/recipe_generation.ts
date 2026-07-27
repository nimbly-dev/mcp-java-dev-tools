import type { AuthResolution } from "@tools-core/auth_resolution";
import { type RecipeStatus } from "@tools-core/recipe_constants.util";
import { buildExecutionReadiness } from "@tools-core/execution_readiness.util";
import { buildRecipeExecutionPlan } from "@tools-core/recipe_execution_plan.util";
import { buildRoutingContext, resolveSelectedMode } from "@tools-core/recipe_intent_routing.util";
import { buildSearchRootsWithAdditional } from "@tools-core/synthesis_search_roots.util";
import type {
  InferenceDiagnostics,
  InferenceFailurePhase,
} from "@tools-core/recipe_types.util";
import { resolveAuthForRecipe } from "../support/recipe_generate/auth_resolve.util";
import {
  defaultStatusForMode,
  buildMissingRequestNextAction,
} from "../support/recipe_generate/mode.util";
import { normalizeRecipeGenerateInput } from "../support/recipe_generate/normalize_input.util";
import { buildRunNotes } from "../support/recipe_generate/run_notes.util";
import { selectAmbiguousCandidates } from "../support/recipe_generate/target_ambiguity.util";
import {
  applyApiBasePathToCandidate,
  applyApiBasePathToTrigger,
  buildMissingRouteAuth,
  buildUnknownTargetAuth,
  deriveApplicationTypeFromSynthesizer,
  mapExecutionInputFailure,
} from "../support/recipe_generate/generation_helpers";
import type {
  GenerateRecipeDeps,
  GenerateRecipeResult,
  RecipeResultType,
} from "@tools-feature-route-synthesis";
import { createDefaultSynthesizerRegistry } from "@tools-registry/plugin.loader";
import { discoverClassMethods, inferTargets } from "./target_inference";

export type { RecipeCandidate, RecipeExecutionPlan } from "@tools-core/recipe_types.util";
export type {
  GenerateRecipeDeps,
  GenerateRecipeResult,
  RecipeResultType,
} from "@tools-feature-route-synthesis";

export async function generateRecipe(
  args: {
    rootAbs: string;
    workspaceRootAbs: string;
    additionalSourceRootsAbs?: string[];
    classHint: string;
    methodHint: string;
    lineHint?: number;
    mappingsBaseUrl?: string;
    discoveryPreference?: "static_only" | "runtime_first" | "runtime_only";
    apiBasePath?: string;
    intentMode: "line_probe" | "regression";
    maxCandidates?: number;
    authToken?: string;
    authUsername?: string;
    authPassword?: string;
    actuationEnabled?: boolean;
    actuationReturnBoolean?: boolean;
    actuationActuatorId?: string;
  },
  deps: GenerateRecipeDeps = {},
): Promise<GenerateRecipeResult> {
  const inferTargetsFn = deps.inferTargetsFn ?? inferTargets;
  const discoverClassMethodsFn = deps.discoverClassMethodsFn ?? discoverClassMethods;
  const resolveAuthForRecipeFn = deps.resolveAuthForRecipeFn ?? resolveAuthForRecipe;
  const synthesizerRegistry = deps.synthesizerRegistry ?? createDefaultSynthesizerRegistry();

  const normalized = normalizeRecipeGenerateInput(args);
  const routingDecision = resolveSelectedMode(
    buildRoutingContext({
      intentMode: normalized.intentMode,
      ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
    }),
  );

  const inferArgs: Parameters<typeof inferTargets>[0] = {
    rootAbs: normalized.rootAbs,
    ...(normalized.additionalSourceRootsAbs?.length
      ? { additionalRootsAbs: normalized.additionalSourceRootsAbs }
      : {}),
    classHint: normalized.classHint,
    methodHint: normalized.methodHint,
    maxCandidates: Math.max(2, normalized.maxCandidates),
  };
  if (typeof normalized.lineHint === "number") inferArgs.lineHint = normalized.lineHint;
  const inferred = await inferTargetsFn(inferArgs);
  let top = inferred.candidates[0];

  let inferenceDiagnosticsBase: InferenceDiagnostics = {
    target: {
      attempted: true,
      matched: Boolean(top),
      candidateCount: inferred.candidates.length,
    },
    request: {
      attempted: true,
      matched: false,
    },
  };

  if (routingDecision.probeIntentRequested && !routingDecision.lineTargetProvided) {
    const auth = buildUnknownTargetAuth();
    const executionPlan = buildRecipeExecutionPlan({
      decision: routingDecision,
      auth,
      actuationEnabled: normalized.actuationEnabled,
      ...(typeof normalized.actuationReturnBoolean === "boolean"
        ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
        : {}),
      ...(normalized.actuationActuatorId
        ? { actuationActuatorId: normalized.actuationActuatorId }
        : {}),
    });
    const readiness = buildExecutionReadiness({
      selectedMode: routingDecision.selectedMode,
      lineTargetProvided: routingDecision.lineTargetProvided,
      auth,
      actuationEnabled: normalized.actuationEnabled,
      ...(typeof normalized.actuationReturnBoolean === "boolean"
        ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
        : {}),
    });
    const runNotes = buildRunNotes({
      selectedMode: routingDecision.selectedMode,
      auth,
      executionPlan,
      readiness: readiness.executionReadiness,
    });
    runNotes.push(
      `inference_target=matched:${String(inferenceDiagnosticsBase.target.matched)} candidates:${inferenceDiagnosticsBase.target.candidateCount}`,
    );
    runNotes.push("inference_request=matched:false");
    runNotes.push("failure_phase=target_inference");
    runNotes.push("failure_reason=line_target_required_for_probe_mode");
    runNotes.push("synthesis_reason_code=line_target_required_for_probe_mode");
    runNotes.push("synthesis_failed_step=intent_routing");
    return {
      requestCandidates: [],
      executionPlan,
      resultType: "report",
      status: "target_not_inferred",
      selectedMode: routingDecision.selectedMode,
      lineTargetProvided: routingDecision.lineTargetProvided,
      probeIntentRequested: routingDecision.probeIntentRequested,
      executionReadiness: readiness.executionReadiness,
      missingInputs: readiness.missingInputs,
      nextAction:
        "Probe intent requires strict line context. Provide lineHint for Class#method:line verification and rerun route_synthesis with action=create_recipe.",
      failurePhase: "target_inference",
      failureReasonCode: "line_target_required_for_probe_mode",
      reasonCode: "line_target_required_for_probe_mode",
      failedStep: "intent_routing",
      attemptedStrategies: ["intent_mode_validation"],
      evidence: [
        `selectedMode=${routingDecision.selectedMode}`,
        `lineTargetProvided=${String(routingDecision.lineTargetProvided)}`,
      ],
      inferenceDiagnostics: inferenceDiagnosticsBase,
      auth,
      notes: runNotes.filter(
        (note) =>
          note.startsWith("execution_readiness=") ||
          note.startsWith("inference_target=") ||
          note.startsWith("inference_request=") ||
          note.startsWith("failure_") ||
          note.startsWith("synthesis_"),
      ),
    };
  }

  if (!top) {
    const classInventory = await discoverClassMethodsFn({
      rootAbs: normalized.rootAbs,
      ...(normalized.additionalSourceRootsAbs?.length
        ? { additionalRootsAbs: normalized.additionalSourceRootsAbs }
        : {}),
      classHint: normalized.classHint,
    });
    const hasSingleEmptyClassMatch =
      classInventory.matchMode === "exact" &&
      classInventory.classes.length === 1 &&
      classInventory.classes[0]?.methods.length === 0;
    if (hasSingleEmptyClassMatch) {
      const classMatch = classInventory.classes[0]!;
      top = {
        file: classMatch.file,
        className: classMatch.className,
        ...(classMatch.fqcn ? { fqcn: classMatch.fqcn } : {}),
        methodName: normalized.methodHint,
        reasons: ["class_inventory_exact_match", "method_bodies=0"],
      };
      inferenceDiagnosticsBase = {
        ...inferenceDiagnosticsBase,
        target: {
          ...inferenceDiagnosticsBase.target,
          matched: true,
        },
      };
    } else {
      const auth = buildUnknownTargetAuth();
      const executionPlan = buildRecipeExecutionPlan({
        decision: routingDecision,
        auth,
        actuationEnabled: normalized.actuationEnabled,
        ...(typeof normalized.actuationReturnBoolean === "boolean"
          ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
          : {}),
        ...(normalized.actuationActuatorId
          ? { actuationActuatorId: normalized.actuationActuatorId }
          : {}),
      });
      const readiness = buildExecutionReadiness({
        selectedMode: routingDecision.selectedMode,
        lineTargetProvided: routingDecision.lineTargetProvided,
        auth,
        actuationEnabled: normalized.actuationEnabled,
        ...(typeof normalized.actuationReturnBoolean === "boolean"
          ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
          : {}),
      });
      const runNotes = buildRunNotes({
        selectedMode: routingDecision.selectedMode,
        ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
        auth,
        executionPlan,
        readiness: readiness.executionReadiness,
      });
      runNotes.push(
        `inference_target=matched:${String(inferenceDiagnosticsBase.target.matched)} candidates:${inferenceDiagnosticsBase.target.candidateCount}`,
      );
      runNotes.push("inference_request=matched:false");
      runNotes.push("failure_phase=target_inference");
      runNotes.push("failure_reason=target_candidate_missing");
      runNotes.push("synthesis_reason_code=target_candidate_missing");
      runNotes.push("synthesis_failed_step=target_inference");
      const attemptedStrategies = ["target_inference_exact_match", "class_inventory_exact_match"];
      const evidence = [
        `classHint=${normalized.classHint}`,
        `methodHint=${normalized.methodHint}`,
        `lineHint=${typeof normalized.lineHint === "number" ? String(normalized.lineHint) : "(none)"}`,
        `candidateCount=${inferred.candidates.length}`,
        `classMatchMode=${classInventory.matchMode}`,
        `classMatchCount=${classInventory.classes.length}`,
      ];
      return {
        requestCandidates: [],
        executionPlan,
        resultType: "report",
        status: "target_not_inferred",
        selectedMode: routingDecision.selectedMode,
        lineTargetProvided: routingDecision.lineTargetProvided,
        probeIntentRequested: routingDecision.probeIntentRequested,
        executionReadiness: readiness.executionReadiness,
        missingInputs: readiness.missingInputs,
        nextAction:
          "Refine classHint/methodHint to exact runtime identifiers (add lineHint for strict probe intent) and rerun route_synthesis with action=create_recipe.",
        failurePhase: "target_inference",
        failureReasonCode: "target_candidate_missing",
        reasonCode: "target_candidate_missing",
        failedStep: "target_inference",
        attemptedStrategies,
        evidence,
        inferenceDiagnostics: inferenceDiagnosticsBase,
        auth,
        notes: runNotes.filter(
          (note) =>
            note.startsWith("execution_readiness=") ||
            note.startsWith("inference_target=") ||
            note.startsWith("inference_request=") ||
            note.startsWith("failure_") ||
            note.startsWith("synthesis_"),
        ),
      };
    }
  }

  const ambiguousCandidates = top
    ? selectAmbiguousCandidates({
        candidates: inferred.candidates,
        classHint: normalized.classHint,
        ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
      })
    : [];
  if (ambiguousCandidates.length > 1) {
    const auth = buildUnknownTargetAuth();
    const executionPlan = buildRecipeExecutionPlan({
      decision: routingDecision,
      auth,
      actuationEnabled: normalized.actuationEnabled,
      ...(typeof normalized.actuationReturnBoolean === "boolean"
        ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
        : {}),
      ...(normalized.actuationActuatorId
        ? { actuationActuatorId: normalized.actuationActuatorId }
        : {}),
    });
    const readiness = buildExecutionReadiness({
      selectedMode: routingDecision.selectedMode,
      lineTargetProvided: routingDecision.lineTargetProvided,
      auth,
      actuationEnabled: normalized.actuationEnabled,
      ...(typeof normalized.actuationReturnBoolean === "boolean"
        ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
        : {}),
    });
    const runNotes = buildRunNotes({
      selectedMode: routingDecision.selectedMode,
      ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
      auth,
      executionPlan,
      readiness: readiness.executionReadiness,
    });
    runNotes.push(
      `inference_target=matched:false candidates:${inferenceDiagnosticsBase.target.candidateCount}`,
    );
    runNotes.push("inference_request=matched:false");
    runNotes.push("failure_phase=target_inference");
    runNotes.push("failure_reason=target_ambiguous");
    runNotes.push("synthesis_reason_code=target_ambiguous");
    runNotes.push("synthesis_failed_step=target_selection");
    return {
      requestCandidates: [],
      executionPlan,
      resultType: "report",
      status: "target_not_inferred",
      selectedMode: routingDecision.selectedMode,
      lineTargetProvided: routingDecision.lineTargetProvided,
      probeIntentRequested: routingDecision.probeIntentRequested,
      executionReadiness: readiness.executionReadiness,
      missingInputs: readiness.missingInputs,
      nextAction:
        "Multiple module candidates matched current hints. Narrow projectRootAbs/additionalSourceRoots to the intended module (or provide disambiguating lineHint) and rerun route_synthesis with action=create_recipe.",
      failurePhase: "target_inference",
      failureReasonCode: "target_ambiguous",
      reasonCode: "target_ambiguous",
      failedStep: "target_selection",
      attemptedStrategies: ["target_inference_exact_match", "target_selection_disambiguation"],
      evidence: [
        `classHint=${normalized.classHint}`,
        `methodHint=${normalized.methodHint}`,
        `lineHint=${typeof normalized.lineHint === "number" ? String(normalized.lineHint) : "(none)"}`,
        `candidateCount=${inferred.candidates.length}`,
        `ambiguousCandidates=${ambiguousCandidates.length}`,
        `ambiguousCandidateFiles=${ambiguousCandidates
          .map((candidate) => candidate.file)
          .slice(0, 8)
          .join("|")}`,
      ],
      inferenceDiagnostics: {
        ...inferenceDiagnosticsBase,
        target: {
          ...inferenceDiagnosticsBase.target,
          matched: false,
        },
      },
      auth,
      notes: runNotes.filter(
        (note) =>
          note.startsWith("execution_readiness=") ||
          note.startsWith("inference_target=") ||
          note.startsWith("inference_request=") ||
          note.startsWith("failure_") ||
          note.startsWith("synthesis_"),
      ),
    };
  }

  const searchRootsAbs = buildSearchRootsWithAdditional(
    normalized.rootAbs,
    normalized.workspaceRootAbs,
    normalized.additionalSourceRootsAbs,
  );
  const synthesis = await synthesizerRegistry.synthesize({
    rootAbs: normalized.rootAbs,
    workspaceRootAbs: normalized.workspaceRootAbs,
    searchRootsAbs,
    classHint: normalized.classHint,
    methodHint: normalized.methodHint,
    intentMode: routingDecision.selectedMode,
    ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
    inferredTargetFileAbs: top.file,
    ...(normalized.mappingsBaseUrl ? { mappingsBaseUrl: normalized.mappingsBaseUrl } : {}),
    discoveryPreference: normalized.discoveryPreference,
    ...(normalized.authToken ? { authToken: normalized.authToken } : {}),
  });

  const synthesisSuccess = synthesis.status === "recipe" ? synthesis : undefined;
  const synthesisFailure = synthesis.status === "report" ? synthesis : undefined;
  let bestRequest = synthesisSuccess?.requestCandidate;
  const matchedControllerFile = synthesisSuccess?.matchedControllerFile;
  const matchedBranchCondition = synthesisSuccess?.matchedBranchCondition;
  const authRootAbs = synthesisSuccess?.matchedRootAbs ?? normalized.rootAbs;
  const synthesizerUsed = synthesisSuccess?.synthesizerUsed ?? synthesisFailure?.synthesizerUsed;
  const applicationType = deriveApplicationTypeFromSynthesizer(synthesizerUsed);
  const attemptedStrategies =
    synthesisSuccess?.attemptedStrategies ?? synthesisFailure?.attemptedStrategies ?? [];
  const evidence = synthesisSuccess?.evidence ?? synthesisFailure?.evidence ?? [];
  let trigger = synthesisSuccess?.trigger;
  let reasonCode: string | undefined = synthesisFailure?.reasonCode;
  let failedStep: string | undefined = synthesisFailure?.failedStep;

  if (bestRequest) {
    bestRequest = applyApiBasePathToCandidate(bestRequest, normalized.apiBasePath);
  }
  if (trigger) {
    trigger = applyApiBasePathToTrigger(trigger, normalized.apiBasePath);
  }

  const inferenceDiagnostics: InferenceDiagnostics = {
    target: inferenceDiagnosticsBase.target,
    request: {
      attempted: true,
      matched: Boolean(bestRequest),
      ...(synthesisSuccess?.requestSource ? { source: synthesisSuccess.requestSource } : {}),
    },
  };

  const inferredTarget: GenerateRecipeResult["inferredTarget"] = {
    file: top.file,
    ...(top.key ? { key: top.key } : {}),
    ...(typeof top.line === "number" ? { line: top.line } : {}),
  };

  const auth: AuthResolution =
    bestRequest || matchedControllerFile
      ? await resolveAuthForRecipeFn({
          projectRootAbs: authRootAbs,
          workspaceRootAbs: normalized.workspaceRootAbs,
          endpointPath: bestRequest?.path,
          controllerFileAbs: matchedControllerFile,
          authToken: normalized.authToken,
          authUsername: normalized.authUsername,
          authPassword: normalized.authPassword,
        })
      : buildMissingRouteAuth({
          genericAuth: {
            provided:
              Boolean(normalized.authToken) ||
              Boolean(normalized.authUsername) ||
              Boolean(normalized.authPassword),
          },
        });

  const executionPlan = buildRecipeExecutionPlan({
    decision: routingDecision,
    auth,
    targetFile: inferredTarget.file,
    actuationEnabled: normalized.actuationEnabled,
    ...(typeof normalized.actuationReturnBoolean === "boolean"
      ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
      : {}),
    ...(normalized.actuationActuatorId
      ? { actuationActuatorId: normalized.actuationActuatorId }
      : {}),
    ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
    ...(inferredTarget.key ? { inferredTargetKey: inferredTarget.key } : {}),
    ...(bestRequest ? { requestCandidate: bestRequest } : {}),
  });

  let resultType: RecipeResultType = "recipe";
  let status: RecipeStatus = defaultStatusForMode(routingDecision.selectedMode);
  let nextAction: string | undefined;
  let failurePhase: InferenceFailurePhase | undefined;
  let failureReasonCode: string | undefined;

  if (!bestRequest) {
    resultType = "report";
    status = "api_request_not_inferred";
    failurePhase = "request_inference";
    failureReasonCode = reasonCode ?? "request_candidate_missing";
    reasonCode = reasonCode ?? "request_candidate_missing";
    failedStep = failedStep ?? "request_synthesis";
    nextAction = synthesisFailure?.nextAction ?? buildMissingRequestNextAction(routingDecision);
  } else if (auth.status === "needs_user_input") {
    nextAction =
      `Missing input: ${(auth.missing ?? ["authToken"]).join(", ")}. ` +
      "Provide missing auth inputs and execute the generated request steps.";
  }

  const readiness = buildExecutionReadiness({
    selectedMode: routingDecision.selectedMode,
    lineTargetProvided: routingDecision.lineTargetProvided,
    auth,
    actuationEnabled: normalized.actuationEnabled,
    ...(typeof normalized.actuationReturnBoolean === "boolean"
      ? { actuationReturnBoolean: normalized.actuationReturnBoolean }
      : {}),
    ...(bestRequest ? { requestCandidate: bestRequest } : {}),
    deterministicRequestInferred: Boolean(
      inferenceDiagnostics.request.matched && inferenceDiagnostics.request.source,
    ),
  });
  if (readiness.executionReadiness === "needs_user_input") {
    resultType = "report";
    if (status !== "api_request_not_inferred" && status !== "target_not_inferred") {
      const readinessFailure = mapExecutionInputFailure(readiness.missingInputs);
      status = "execution_input_required";
      failurePhase = readinessFailure?.failurePhase ?? "auth_resolution";
      failureReasonCode = readinessFailure?.failureReasonCode ?? "auth_input_required";
      failedStep = failedStep ?? readinessFailure?.failedStep;
      reasonCode = reasonCode ?? readinessFailure?.failureReasonCode;
    }
    if (!nextAction && readiness.nextAction) nextAction = readiness.nextAction;
  }

  const runNotes = buildRunNotes({
    selectedMode: routingDecision.selectedMode,
    ...(typeof normalized.lineHint === "number" ? { lineHint: normalized.lineHint } : {}),
    ...(typeof inferredTarget?.line === "number" ? { inferredLine: inferredTarget.line } : {}),
    ...(bestRequest ? { bestRequest } : {}),
    ...(matchedBranchCondition ? { matchedBranchCondition: matchedBranchCondition } : {}),
    auth,
    executionPlan,
    readiness: readiness.executionReadiness,
  });
  runNotes.push(
    `inference_target=matched:${String(inferenceDiagnostics.target.matched)} candidates:${inferenceDiagnostics.target.candidateCount}`,
  );
  runNotes.push(
    `inference_request=matched:${String(inferenceDiagnostics.request.matched)}` +
      (inferenceDiagnostics.request.source ? ` source:${inferenceDiagnostics.request.source}` : ""),
  );
  if (failurePhase) runNotes.push(`failure_phase=${failurePhase}`);
  if (failureReasonCode) runNotes.push(`failure_reason=${failureReasonCode}`);
  if (reasonCode) runNotes.push(`synthesis_reason_code=${reasonCode}`);
  if (failedStep) runNotes.push(`synthesis_failed_step=${failedStep}`);
  if (synthesizerUsed) runNotes.push(`synthesizer_used=${synthesizerUsed}`);
  if (applicationType) runNotes.push(`application_type=${applicationType}`);
  if (bestRequest && !normalized.apiBasePath) {
    runNotes.push(
      "context_path_hint=Optional apiBasePath (for example /api/v1) can be supplied when runtime uses a context path.",
    );
  }
  if (resultType === "report") {
    if (!reasonCode) reasonCode = failureReasonCode;
    if (!failedStep) failedStep = failurePhase;
  }
  const notesForOutput =
    resultType === "report"
      ? runNotes.filter(
          (note) =>
            note.startsWith("execution_readiness=") ||
            note.startsWith("inference_target=") ||
            note.startsWith("inference_request=") ||
            note.startsWith("failure_") ||
            note.startsWith("synthesis_"),
        )
      : runNotes;

  return {
    ...(inferredTarget ? { inferredTarget } : {}),
    requestCandidates: bestRequest ? [bestRequest] : [],
    executionPlan,
    resultType,
    status,
    selectedMode: routingDecision.selectedMode,
    lineTargetProvided: routingDecision.lineTargetProvided,
    probeIntentRequested: routingDecision.probeIntentRequested,
    executionReadiness: readiness.executionReadiness,
    missingInputs: readiness.missingInputs,
    ...(nextAction ? { nextAction } : {}),
    ...(failurePhase ? { failurePhase } : {}),
    ...(failureReasonCode ? { failureReasonCode } : {}),
    ...(reasonCode ? { reasonCode } : {}),
    ...(failedStep ? { failedStep } : {}),
    ...(synthesizerUsed ? { synthesizerUsed } : {}),
    ...(applicationType ? { applicationType } : {}),
    ...(trigger ? { trigger } : {}),
    attemptedStrategies,
    evidence,
    inferenceDiagnostics,
    auth,
    notes: notesForOutput,
  };
}
