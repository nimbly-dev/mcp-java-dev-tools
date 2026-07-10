import * as path from "node:path";

import type { RouteSynthesisRecipeGenerationDeps } from "@/models/route_synthesis.model";
import { renderRecipeTemplate } from "@/lib/recipe_template";
import { buildRecipeTemplateModel } from "@/models/recipe_output_model";
import { validateProjectRootAbs } from "@tools-core/project_root_validate";
import { deriveNextActionCode, normalizeReasonMeta } from "@tools-core/failure_diagnostics";
import { enrichRuntimeCapture } from "@/utils/recipe_generate/runtime_capture_enrich.util";
import { resolveAdditionalSourceRoots } from "@/utils/source_roots_resolve.util";
import { generateRecipe } from "../shared/recipe_generation";

function resolveProbeBaseUrlForRecipe(args: {
  defaultProbeBaseUrl: RouteSynthesisRecipeGenerationDeps["probeBaseUrl"];
  probeId?: string;
  probeBaseUrl?: string;
  probeRegistry?: ReturnType<NonNullable<RouteSynthesisRecipeGenerationDeps["getProbeRegistry"]>>;
}): { ok: true; probeBaseUrl: string } | { ok: false; reasonCode: string; reason: string } {
  if (typeof args.probeBaseUrl === "string" && args.probeBaseUrl.trim().length > 0) {
    return { ok: true, probeBaseUrl: args.probeBaseUrl.trim() };
  }
  if (typeof args.probeId === "string" && args.probeId.trim().length > 0) {
    const probe = args.probeRegistry?.probesById.get(args.probeId.trim());
    if (!probe) {
      return {
        ok: false,
        reasonCode: "probe_id_unknown",
        reason: `probeId '${args.probeId.trim()}' is not configured in active probe registry profile.`,
      };
    }
    return { ok: true, probeBaseUrl: probe.baseUrl };
  }
  return { ok: true, probeBaseUrl: args.defaultProbeBaseUrl };
}

const RECIPE_REASON_META_KEYS = ["failedStep", "classHint", "methodHint", "lineHint", "selectedMode"] as const;

function isFqcn(value: string): boolean {
  const trimmed = value.trim();
  if (!trimmed.includes(".")) return false;
  const segments = trimmed.split(".");
  if (segments.some((segment) => segment.length === 0)) return false;
  return segments.every((segment) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(segment));
}

function toActionCode(step: { title: string }): string {
  const title = step.title.trim().toLowerCase();
  if (title === "resolve authentication") return "resolve_auth";
  if (title === "request candidate missing") return "request_candidate_missing";
  if (title === "return report") return "return_report";
  if (title === "line target unresolved") return "line_target_unresolved";
  if (title === "reset probe baseline") return "probe_reset_baseline";
  if (title === "execute regression api check") return "execute_api_check";
  if (title === "verify api regression outcome") return "verify_api_regression";
  if (title === "execute probe trigger request") return "execute_probe_trigger";
  if (title === "verify single-line probe hit") return "verify_probe_hit";
  if (title === "verify api and line probe outcomes") return "verify_api_and_probe";
  if (title === "enable branch actuation") return "enable_actuation";
  if (title === "disable branch actuation") return "disable_actuation";
  return title.replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

function compactRoutingReason(selectedMode: string): string {
  if (selectedMode === "regression") return "regression_no_probe";
  if (selectedMode === "single_line_probe") return "single_line_probe";
  if (selectedMode === "regression_plus_line_probe") return "regression_plus_line_probe";
  return "mode_selected";
}

function compactExecutionPlanForOutput(args: {
  resultType: "recipe" | "report";
  executionPlan: {
    selectedMode: string;
    routingReason: string;
    steps: Array<{ phase: string; title: string; instruction: string }>;
    probeCallPlan: unknown;
  };
}) {
  if (args.resultType !== "report") return args.executionPlan;
  return {
    selectedMode: args.executionPlan.selectedMode,
    routingReason: compactRoutingReason(args.executionPlan.selectedMode),
    steps: args.executionPlan.steps.map((step) => ({
      phase: step.phase,
      actionCode: toActionCode(step),
    })),
    probeCallPlan: args.executionPlan.probeCallPlan,
  };
}

function compactExecutionPlanForText(executionPlan: {
  selectedMode: string;
  routingReason: string;
  steps: Array<{ phase: string; title: string; instruction: string }>;
  probeCallPlan: unknown;
}) {
  return {
    selectedMode: executionPlan.selectedMode,
    routingReason: compactRoutingReason(executionPlan.selectedMode),
    steps: executionPlan.steps.map((step) => ({
      phase: step.phase,
      actionCode: toActionCode(step),
    })),
    probeCallPlan: executionPlan.probeCallPlan,
  };
}

export async function runRecipeCreate(
  input: Record<string, unknown>,
  deps: RouteSynthesisRecipeGenerationDeps,
): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  const inputRecord = input as {
    projectRootAbs: string;
    classHint: string;
    methodHint: string;
    lineHint?: number;
    mappingsBaseUrl?: string;
    discoveryPreference?: "static_only" | "runtime_first" | "runtime_only";
    additionalSourceRoots?: string[];
    apiBasePath?: string;
    intentMode: "line_probe" | "regression";
    authToken?: string;
    authUsername?: string;
    authPassword?: string;
    actuationEnabled?: boolean;
    actuationReturnBoolean?: boolean;
    actuationActuatorId?: string;
    outputTemplate?: string;
    probeId?: string;
    probeBaseUrl?: string;
  };
  const inputHints = {
    classHint: typeof inputRecord.classHint === "string" ? inputRecord.classHint : undefined,
    methodHint: typeof inputRecord.methodHint === "string" ? inputRecord.methodHint : undefined,
    lineHint: typeof inputRecord.lineHint === "number" ? inputRecord.lineHint : undefined,
    mappingsBaseUrl:
      typeof inputRecord.mappingsBaseUrl === "string" ? inputRecord.mappingsBaseUrl : undefined,
    discoveryPreference:
      inputRecord.discoveryPreference === "static_only" ||
      inputRecord.discoveryPreference === "runtime_first" ||
      inputRecord.discoveryPreference === "runtime_only"
        ? inputRecord.discoveryPreference
        : undefined,
    additionalSourceRoots:
      Array.isArray(inputRecord.additionalSourceRoots) &&
      inputRecord.additionalSourceRoots.every((value) => typeof value === "string")
        ? inputRecord.additionalSourceRoots
        : undefined,
    apiBasePath: typeof inputRecord.apiBasePath === "string" ? inputRecord.apiBasePath : undefined,
    actuationEnabled:
      typeof inputRecord.actuationEnabled === "boolean" ? inputRecord.actuationEnabled : undefined,
    actuationReturnBoolean:
      typeof inputRecord.actuationReturnBoolean === "boolean"
        ? inputRecord.actuationReturnBoolean
        : undefined,
    actuationActuatorId:
      typeof inputRecord.actuationActuatorId === "string"
        ? inputRecord.actuationActuatorId
        : undefined,
    probeId: typeof inputRecord.probeId === "string" ? inputRecord.probeId : undefined,
    probeBaseUrl:
      typeof inputRecord.probeBaseUrl === "string" ? inputRecord.probeBaseUrl : undefined,
  };
  const probeResolveInput: Parameters<typeof resolveProbeBaseUrlForRecipe>[0] = {
    defaultProbeBaseUrl: deps.probeBaseUrl,
  };
  if (typeof inputRecord.probeId === "string") probeResolveInput.probeId = inputRecord.probeId;
  if (typeof inputRecord.probeBaseUrl === "string") {
    probeResolveInput.probeBaseUrl = inputRecord.probeBaseUrl;
  }
  if (deps.getProbeRegistry) {
    const registry = deps.getProbeRegistry();
    if (registry) probeResolveInput.probeRegistry = registry;
  }
  const probeResolve = resolveProbeBaseUrlForRecipe(probeResolveInput);
  if (!probeResolve.ok) {
    const structuredContent = {
      projectRoot: inputRecord.projectRootAbs,
      hints: inputHints,
      resultType: "report",
      status: "blocked_invalid",
      reasonCode: probeResolve.reasonCode,
      nextActionCode: deriveNextActionCode(probeResolve.reasonCode),
      failedStep: "input_validation",
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "input_validation",
          classHint: inputRecord.classHint,
          methodHint: inputRecord.methodHint,
          lineHint: inputRecord.lineHint,
        },
        RECIPE_REASON_META_KEYS,
      ),
      evidence: [probeResolve.reason],
      attemptedStrategies: ["probe_selection_validation"],
      reason: probeResolve.reason,
      nextAction:
        "Provide a valid probeId from artifact_management (artifactType=probe_config, action=read) or explicit probeBaseUrl and rerun route_synthesis with action=create_recipe.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const validated = await validateProjectRootAbs(inputRecord.projectRootAbs);
  if (!validated.ok) {
    const reasonCode = validated.status;
    const structuredContent = {
      projectRoot: validated.value ?? inputRecord.projectRootAbs ?? "(project_root_unset)",
      hints: inputHints,
      resultType: "report",
      status: reasonCode,
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "project_root_validation",
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "project_root_validation",
          classHint: inputRecord.classHint,
          methodHint: inputRecord.methodHint,
          lineHint: inputRecord.lineHint,
        },
        RECIPE_REASON_META_KEYS,
      ),
      evidence: [validated.reason],
      attemptedStrategies: ["project_root_validation"],
      reason: validated.reason,
      nextAction: validated.nextAction,
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const projectRoot = validated.projectRootAbs;
  const additionalRoots = await resolveAdditionalSourceRoots({
    workspaceRootAbs: deps.workspaceRootAbs,
    ...(Array.isArray(inputRecord.additionalSourceRoots) &&
    inputRecord.additionalSourceRoots.every((value) => typeof value === "string")
      ? { additionalSourceRoots: inputRecord.additionalSourceRoots as string[] }
      : {}),
  });
  if (!additionalRoots.ok) {
    const reasonCode = additionalRoots.reasonCode;
    const structuredContent = {
      projectRoot,
      hints: inputHints,
      resultType: "report",
      status: "project_selector_invalid",
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: additionalRoots.failedStep,
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: additionalRoots.failedStep,
          classHint: inputRecord.classHint,
          methodHint: inputRecord.methodHint,
          lineHint: inputRecord.lineHint,
        },
        RECIPE_REASON_META_KEYS,
      ),
      evidence: additionalRoots.evidence,
      attemptedStrategies: ["additional_source_roots_validation"],
      reason: additionalRoots.reason,
      nextAction: additionalRoots.nextAction,
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  if (!isFqcn(inputRecord.classHint)) {
    const reasonCode = "class_hint_not_fqcn";
    const structuredContent = {
      projectRoot,
      hints: inputHints,
      resultType: "report",
      status: reasonCode,
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "input_validation",
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "input_validation",
          classHint: inputRecord.classHint,
          methodHint: inputRecord.methodHint,
          lineHint: inputRecord.lineHint,
        },
        RECIPE_REASON_META_KEYS,
      ),
      evidence: [`classHint=${inputRecord.classHint}`],
      attemptedStrategies: ["class_hint_validation"],
      reason: "classHint must be a fully qualified class name (FQCN).",
      nextAction:
        "Provide exact FQCN in classHint (for example: com.acme.catalog.web.controller.CatalogShoeController) and rerun route_synthesis with action=create_recipe.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const generateArgs: Parameters<typeof generateRecipe>[0] = {
    rootAbs: projectRoot,
    workspaceRootAbs: deps.workspaceRootAbs,
    ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
      ? { additionalSourceRootsAbs: additionalRoots.normalizedAdditionalSourceRoots }
      : {}),
    classHint: inputRecord.classHint,
    methodHint: inputRecord.methodHint,
    intentMode: inputRecord.intentMode,
  };
  if (typeof inputRecord.lineHint === "number") generateArgs.lineHint = inputRecord.lineHint;
  if (typeof inputRecord.mappingsBaseUrl === "string") {
    generateArgs.mappingsBaseUrl = inputRecord.mappingsBaseUrl;
  }
  if (typeof inputRecord.discoveryPreference === "string") {
    generateArgs.discoveryPreference = inputRecord.discoveryPreference;
  }
  if (typeof inputRecord.apiBasePath === "string") generateArgs.apiBasePath = inputRecord.apiBasePath;
  if (inputRecord.authToken) generateArgs.authToken = inputRecord.authToken;
  if (inputRecord.authUsername) generateArgs.authUsername = inputRecord.authUsername;
  if (inputRecord.authPassword) generateArgs.authPassword = inputRecord.authPassword;
  if (typeof inputRecord.actuationEnabled === "boolean") {
    generateArgs.actuationEnabled = inputRecord.actuationEnabled;
  }
  if (typeof inputRecord.actuationReturnBoolean === "boolean") {
    generateArgs.actuationReturnBoolean = inputRecord.actuationReturnBoolean;
  }
  if (inputRecord.actuationActuatorId) {
    generateArgs.actuationActuatorId = inputRecord.actuationActuatorId;
  }

  const generated = await generateRecipe(generateArgs);
  const modelArgs: Parameters<typeof buildRecipeTemplateModel>[0] = {
    classHint: inputRecord.classHint,
    methodHint: inputRecord.methodHint,
    generated,
  };
  if (typeof inputRecord.lineHint === "number") modelArgs.lineHint = inputRecord.lineHint;
  const model = buildRecipeTemplateModel(modelArgs);
  const hasExplicitTemplate =
    typeof inputRecord.outputTemplate === "string" && inputRecord.outputTemplate.trim().length > 0;
  const template = hasExplicitTemplate ? inputRecord.outputTemplate : undefined;
  const rendered = template ? renderRecipeTemplate(template, model) : undefined;

  const inferredKey = generated.inferredTarget?.key;
  const inferredLine =
    typeof inputRecord.lineHint === "number"
      ? inputRecord.lineHint
      : typeof generated.inferredTarget?.line === "number"
        ? generated.inferredTarget.line
        : undefined;
  const runtimeCapture = await enrichRuntimeCapture({
    ...(inferredKey ? { inferredKey } : {}),
    ...(typeof inferredLine === "number" ? { inferredLine } : {}),
    probeBaseUrl: probeResolve.probeBaseUrl,
    probeStatusPath: deps.probeStatusPath,
  });

  const strictRuntimeLineUnresolved =
    generated.resultType === "recipe" &&
    generated.probeIntentRequested &&
    typeof inferredLine === "number" &&
    runtimeCapture.lineValidation === "invalid_line_target";

  const normalizedGenerated = strictRuntimeLineUnresolved
    ? {
        ...generated,
        requestCandidates: [],
        resultType: "report" as const,
        status: "target_not_inferred" as const,
        executionReadiness: "needs_user_input" as const,
        missingInputs: [
          {
            category: "probe" as const,
            field: "lineHint",
            reason: "runtime_line_unresolved",
            suggestedAction:
              "Use route_synthesis with action=class_methods or action=infer_target to select a runtime-resolvable line and rerun route_synthesis with action=create_recipe.",
          },
        ],
        nextAction:
          "Strict line target is not runtime-resolvable for current JVM/source alignment. Select a validated runtime line via route_synthesis and rerun action=create_recipe.",
        nextActionCode: "select_resolvable_line",
        failurePhase: "target_inference" as const,
        failureReasonCode: "runtime_line_unresolved",
        reasonCode: "runtime_line_unresolved",
        failedStep: "line_validation",
        reasonMeta: {
          failedStep: "line_validation",
          classHint: inputRecord.classHint,
          methodHint: inputRecord.methodHint,
          lineHint: inferredLine,
          selectedMode: generated.selectedMode,
        },
        attemptedStrategies: [
          ...generated.attemptedStrategies,
          "runtime_line_validation_precheck",
        ],
        evidence: [
          ...generated.evidence,
          `probeKey=${inferredKey ?? "(missing)"}:${inferredLine}`,
          "lineValidation=invalid_line_target",
        ],
        notes: [
          ...(generated.notes ?? []).filter(
            (note) =>
              note.startsWith("execution_readiness=") ||
              note.startsWith("inference_target=") ||
              note.startsWith("inference_request=") ||
              note.startsWith("failure_") ||
              note.startsWith("synthesis_"),
          ),
          "failure_phase=target_inference",
          "failure_reason=runtime_line_unresolved",
          "synthesis_reason_code=runtime_line_unresolved",
          "synthesis_failed_step=line_validation",
        ],
      }
    : generated;

  const effectiveReasonCode =
    normalizedGenerated.resultType === "report"
      ? normalizedGenerated.reasonCode ??
        normalizedGenerated.failureReasonCode ??
        normalizedGenerated.status
      : undefined;
  const effectiveNextActionCode =
    normalizedGenerated.resultType === "report"
      ? normalizedGenerated.nextActionCode ?? deriveNextActionCode(effectiveReasonCode)
      : undefined;
  const effectiveReasonMeta =
    normalizedGenerated.resultType === "report"
      ? normalizeReasonMeta(
          normalizedGenerated.reasonMeta ?? {
            failedStep: normalizedGenerated.failedStep,
            classHint: inputRecord.classHint,
            methodHint: inputRecord.methodHint,
            lineHint: inputRecord.lineHint,
            selectedMode: normalizedGenerated.selectedMode,
          },
          RECIPE_REASON_META_KEYS,
        )
      : undefined;

  const structuredContent = {
    projectRoot,
    hints: {
      classHint: inputRecord.classHint,
      methodHint: inputRecord.methodHint,
      lineHint: inputRecord.lineHint,
      mappingsBaseUrl: inputRecord.mappingsBaseUrl,
      discoveryPreference: inputRecord.discoveryPreference,
      additionalSourceRoots:
        additionalRoots.normalizedAdditionalSourceRoots.length > 0
          ? additionalRoots.normalizedAdditionalSourceRoots
          : undefined,
      apiBasePath: inputRecord.apiBasePath,
      actuationEnabled: inputRecord.actuationEnabled,
      actuationReturnBoolean: inputRecord.actuationReturnBoolean,
      actuationActuatorId: inputRecord.actuationActuatorId,
    },
    inferredTarget: generated.inferredTarget
      ? {
          ...generated.inferredTarget,
          file: path.relative(projectRoot, generated.inferredTarget.file),
        }
      : undefined,
    requestCandidates: normalizedGenerated.requestCandidates,
    executionPlan: compactExecutionPlanForOutput({
      resultType: normalizedGenerated.resultType,
      executionPlan: normalizedGenerated.executionPlan,
    }),
    resultType: normalizedGenerated.resultType,
    status: normalizedGenerated.status,
    selectedMode: normalizedGenerated.selectedMode,
    lineTargetProvided: normalizedGenerated.lineTargetProvided,
    probeIntentRequested: normalizedGenerated.probeIntentRequested,
    executionReadiness: normalizedGenerated.executionReadiness,
    missingInputs: normalizedGenerated.missingInputs,
    ...(normalizedGenerated.nextAction ? { nextAction: normalizedGenerated.nextAction } : {}),
    ...(effectiveNextActionCode ? { nextActionCode: effectiveNextActionCode } : {}),
    ...(normalizedGenerated.failurePhase ? { failurePhase: normalizedGenerated.failurePhase } : {}),
    ...(normalizedGenerated.failureReasonCode
      ? { failureReasonCode: normalizedGenerated.failureReasonCode }
      : {}),
    ...(effectiveReasonCode ? { reasonCode: effectiveReasonCode } : {}),
    ...(normalizedGenerated.failedStep ? { failedStep: normalizedGenerated.failedStep } : {}),
    ...(effectiveReasonMeta ? { reasonMeta: effectiveReasonMeta } : {}),
    ...(normalizedGenerated.synthesizerUsed
      ? { synthesizerUsed: normalizedGenerated.synthesizerUsed }
      : {}),
    ...(normalizedGenerated.applicationType
      ? { applicationType: normalizedGenerated.applicationType }
      : {}),
    ...(normalizedGenerated.trigger ? { trigger: normalizedGenerated.trigger } : {}),
    attemptedStrategies: normalizedGenerated.attemptedStrategies,
    evidence: normalizedGenerated.evidence,
    inferenceDiagnostics: normalizedGenerated.inferenceDiagnostics,
    auth: normalizedGenerated.auth,
    notes: normalizedGenerated.notes,
    runtimeCapture,
    ...(rendered ? { rendered } : {}),
  };

  const internalContent = {
    resultType: normalizedGenerated.resultType,
    status: normalizedGenerated.status,
    selectedMode: normalizedGenerated.selectedMode,
    lineTargetProvided: normalizedGenerated.lineTargetProvided,
    probeIntentRequested: normalizedGenerated.probeIntentRequested,
    executionReadiness: normalizedGenerated.executionReadiness,
    missingInputs: normalizedGenerated.missingInputs,
    ...(normalizedGenerated.nextAction ? { nextAction: normalizedGenerated.nextAction } : {}),
    ...(effectiveNextActionCode ? { nextActionCode: effectiveNextActionCode } : {}),
    ...(normalizedGenerated.failurePhase ? { failurePhase: normalizedGenerated.failurePhase } : {}),
    ...(normalizedGenerated.failureReasonCode
      ? { failureReasonCode: normalizedGenerated.failureReasonCode }
      : {}),
    ...(effectiveReasonCode ? { reasonCode: effectiveReasonCode } : {}),
    ...(normalizedGenerated.failedStep ? { failedStep: normalizedGenerated.failedStep } : {}),
    ...(effectiveReasonMeta ? { reasonMeta: effectiveReasonMeta } : {}),
    ...(normalizedGenerated.synthesizerUsed
      ? { synthesizerUsed: normalizedGenerated.synthesizerUsed }
      : {}),
    ...(normalizedGenerated.applicationType
      ? { applicationType: normalizedGenerated.applicationType }
      : {}),
    ...(normalizedGenerated.trigger
      ? {
          trigger: {
            kind: normalizedGenerated.trigger.kind,
            method: normalizedGenerated.trigger.method,
            path: normalizedGenerated.trigger.path,
            queryTemplate: normalizedGenerated.trigger.queryTemplate,
          },
        }
      : {}),
    attemptedStrategies: normalizedGenerated.attemptedStrategies.slice(0, 6),
    inferenceDiagnostics: normalizedGenerated.inferenceDiagnostics,
    routingReason: normalizedGenerated.executionPlan.routingReason,
    inferredTarget: structuredContent.inferredTarget,
    requestCandidates: normalizedGenerated.requestCandidates.map((candidate) => ({
      method: candidate.method,
      path: candidate.path,
      queryTemplate: candidate.queryTemplate,
    })),
    executionPlan: compactExecutionPlanForText(normalizedGenerated.executionPlan),
    auth: normalizedGenerated.auth,
    runtimeCapture:
      runtimeCapture.status === "available"
        ? {
            status: "available",
            capturePreview: {
              available: true,
              captureId: runtimeCapture.capturePreview?.captureId,
              capturedAtEpoch: runtimeCapture.capturePreview?.capturedAtEpoch,
            },
            lineValidation: runtimeCapture.lineValidation,
            lineResolvable: runtimeCapture.lineResolvable,
          }
        : runtimeCapture,
    notes: normalizedGenerated.notes.slice(0, 6),
  };
  return {
    content: [
      {
        type: "text",
        text: rendered ?? JSON.stringify(internalContent, null, 2),
      },
    ],
    structuredContent,
  };
}
