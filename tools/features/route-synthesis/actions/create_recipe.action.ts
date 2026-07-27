import * as path from "node:path";

import type { RouteSynthesisRecipeGenerationDeps } from "@tools-feature-route-synthesis";
import { renderRecipeTemplate } from "../support/recipe_template";
import { buildRecipeTemplateModel } from "@tools-feature-route-synthesis";
import { validateProjectRootAbs } from "@tools-core/project_root_validate";
import { deriveNextActionCode, normalizeReasonMeta } from "@tools-core/failure_diagnostics";
import { enrichRuntimeCapture } from "../support/recipe_generate/runtime_capture_enrich.util";
import { resolveProbeBaseUrl } from "../support/probe_base_url_resolve";
import { normalizeRecipeCreateInput } from "../support/recipe_create_input";
import {
  compactExecutionPlanForOutput,
  compactExecutionPlanForText,
  isFqcn,
  RECIPE_REASON_META_KEYS,
} from "../support/recipe_create_output";
import { resolveAdditionalSourceRoots } from "../support/source_roots_resolve";
import { generateRecipe } from "../shared/recipe_generation";

export async function runRecipeCreate(
  input: Record<string, unknown>,
  deps: RouteSynthesisRecipeGenerationDeps,
): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  const { inputRecord, inputHints } = normalizeRecipeCreateInput(input);
  const probeResolveInput: Parameters<typeof resolveProbeBaseUrl>[0] = {
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
  const probeResolve = resolveProbeBaseUrl(probeResolveInput);
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
  if (typeof inputRecord.apiBasePath === "string")
    generateArgs.apiBasePath = inputRecord.apiBasePath;
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
        attemptedStrategies: [...generated.attemptedStrategies, "runtime_line_validation_precheck"],
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
      ? (normalizedGenerated.reasonCode ??
        normalizedGenerated.failureReasonCode ??
        normalizedGenerated.status)
      : undefined;
  const effectiveNextActionCode =
    normalizedGenerated.resultType === "report"
      ? (normalizedGenerated.nextActionCode ?? deriveNextActionCode(effectiveReasonCode))
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
