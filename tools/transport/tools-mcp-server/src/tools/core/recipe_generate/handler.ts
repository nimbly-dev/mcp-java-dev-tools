import * as path from "node:path";

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { renderRecipeTemplate } from "@/lib/recipe_template";
import { buildRecipeTemplateModel } from "@/models/recipe_output_model";
import { validateProjectRootAbs } from "@/utils/project_root_validate.util";
import { deriveNextActionCode, normalizeReasonMeta } from "@/utils/failure_diagnostics.util";
import { enrichRuntimeCapture } from "@/utils/recipe_generate/runtime_capture_enrich.util";
import { resolveAdditionalSourceRoots } from "@/utils/source_roots_resolve.util";
import { generateRecipe } from "@/tools/core/recipe_generate/domain";
import { RECIPE_CREATE_TOOL } from "@/tools/core/recipe_generate/contract";
import type { ProbeRegistry } from "@/config/probe-registry";

export type RecipeGenerateHandlerDeps = {
  probeBaseUrl: string;
  probeStatusPath: string;
  workspaceRootAbs: string;
  getProbeRegistry?: () => ProbeRegistry | undefined;
};

function resolveProbeBaseUrlForRecipe(args: {
  defaultProbeBaseUrl: string;
  probeId?: string;
  probeBaseUrl?: string;
  probeRegistry?: ProbeRegistry;
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

export function registerRecipeCreateTool(
  server: McpServer,
  deps: RecipeGenerateHandlerDeps,
): void {
  server.registerTool(
    RECIPE_CREATE_TOOL.name,
    {
      description: RECIPE_CREATE_TOOL.description,
      inputSchema: RECIPE_CREATE_TOOL.inputSchema,
    },
    async (input) => {
      const inputHints = {
        classHint: typeof input.classHint === "string" ? input.classHint : undefined,
        methodHint: typeof input.methodHint === "string" ? input.methodHint : undefined,
        lineHint: typeof input.lineHint === "number" ? input.lineHint : undefined,
        mappingsBaseUrl:
          typeof input.mappingsBaseUrl === "string" ? input.mappingsBaseUrl : undefined,
        discoveryPreference:
          input.discoveryPreference === "static_only" ||
          input.discoveryPreference === "runtime_first" ||
          input.discoveryPreference === "runtime_only"
            ? input.discoveryPreference
            : undefined,
        additionalSourceRoots:
          Array.isArray(input.additionalSourceRoots) &&
          input.additionalSourceRoots.every((value) => typeof value === "string")
            ? input.additionalSourceRoots
            : undefined,
        apiBasePath: typeof input.apiBasePath === "string" ? input.apiBasePath : undefined,
        actuationEnabled:
          typeof input.actuationEnabled === "boolean" ? input.actuationEnabled : undefined,
        actuationReturnBoolean:
          typeof input.actuationReturnBoolean === "boolean"
            ? input.actuationReturnBoolean
            : undefined,
        actuationActuatorId:
          typeof input.actuationActuatorId === "string" ? input.actuationActuatorId : undefined,
        probeId: typeof input.probeId === "string" ? input.probeId : undefined,
        probeBaseUrl: typeof input.probeBaseUrl === "string" ? input.probeBaseUrl : undefined,
      };
      const {
        projectRootAbs,
        classHint,
        methodHint,
        lineHint,
        mappingsBaseUrl,
        discoveryPreference,
        additionalSourceRoots,
        apiBasePath,
        intentMode,
        authToken,
        authUsername,
        authPassword,
        actuationEnabled,
        actuationReturnBoolean,
        actuationActuatorId,
        outputTemplate,
        probeId,
        probeBaseUrl,
      } = input;
      const probeResolveInput: Parameters<typeof resolveProbeBaseUrlForRecipe>[0] = {
        defaultProbeBaseUrl: deps.probeBaseUrl,
      };
      if (typeof probeId === "string") probeResolveInput.probeId = probeId;
      if (typeof probeBaseUrl === "string") probeResolveInput.probeBaseUrl = probeBaseUrl;
      if (deps.getProbeRegistry) {
        const registry = deps.getProbeRegistry();
        if (registry) probeResolveInput.probeRegistry = registry;
      }
      const probeResolve = resolveProbeBaseUrlForRecipe(probeResolveInput);
      if (!probeResolve.ok) {
        const structuredContent = {
          projectRoot: projectRootAbs,
          hints: inputHints,
          resultType: "report",
          status: "blocked_invalid",
          reasonCode: probeResolve.reasonCode,
          nextActionCode: deriveNextActionCode(probeResolve.reasonCode),
          failedStep: "input_validation",
          reasonMeta: normalizeReasonMeta(
            { failedStep: "input_validation", classHint, methodHint, lineHint },
            RECIPE_REASON_META_KEYS,
          ),
          evidence: [probeResolve.reason],
          attemptedStrategies: ["probe_selection_validation"],
          reason: probeResolve.reason,
          nextAction:
            "Provide a valid probeId from artifact_management (artifactType=probe_config, action=read) or explicit probeBaseUrl and rerun probe_recipe_create.",
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }

      const validated = await validateProjectRootAbs(projectRootAbs);
      if (!validated.ok) {
        const reasonCode = validated.status;
        const structuredContent = {
          projectRoot: validated.value ?? projectRootAbs ?? "(project_root_unset)",
          hints: inputHints,
          resultType: "report",
          status: reasonCode,
          reasonCode,
          nextActionCode: deriveNextActionCode(reasonCode),
          failedStep: "project_root_validation",
          reasonMeta: normalizeReasonMeta(
            { failedStep: "project_root_validation", classHint, methodHint, lineHint },
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
        ...(Array.isArray(additionalSourceRoots) &&
        additionalSourceRoots.every((value) => typeof value === "string")
          ? { additionalSourceRoots: additionalSourceRoots as string[] }
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
            { failedStep: additionalRoots.failedStep, classHint, methodHint, lineHint },
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

      if (!isFqcn(classHint)) {
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
            { failedStep: "input_validation", classHint, methodHint, lineHint },
            RECIPE_REASON_META_KEYS,
          ),
          evidence: [`classHint=${classHint}`],
          attemptedStrategies: ["class_hint_validation"],
          reason: "classHint must be a fully qualified class name (FQCN).",
          nextAction:
            "Provide exact FQCN in classHint (for example: com.acme.catalog.web.controller.CatalogShoeController) and rerun probe_recipe_create.",
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
        classHint,
        methodHint,
        intentMode,
      };
      if (typeof lineHint === "number") generateArgs.lineHint = lineHint;
      if (typeof mappingsBaseUrl === "string") generateArgs.mappingsBaseUrl = mappingsBaseUrl;
      if (typeof discoveryPreference === "string") {
        generateArgs.discoveryPreference = discoveryPreference;
      }
      if (typeof apiBasePath === "string") generateArgs.apiBasePath = apiBasePath;
      if (authToken) generateArgs.authToken = authToken;
      if (authUsername) generateArgs.authUsername = authUsername;
      if (authPassword) generateArgs.authPassword = authPassword;
      if (typeof actuationEnabled === "boolean") generateArgs.actuationEnabled = actuationEnabled;
      if (typeof actuationReturnBoolean === "boolean") {
        generateArgs.actuationReturnBoolean = actuationReturnBoolean;
      }
      if (actuationActuatorId) generateArgs.actuationActuatorId = actuationActuatorId;

      const generated = await generateRecipe(generateArgs);
      const modelArgs: Parameters<typeof buildRecipeTemplateModel>[0] = {
        classHint,
        methodHint,
        generated,
      };
      if (typeof lineHint === "number") modelArgs.lineHint = lineHint;
      const model = buildRecipeTemplateModel(modelArgs);
      const hasExplicitTemplate =
        typeof outputTemplate === "string" && outputTemplate.trim().length > 0;
      const template = hasExplicitTemplate ? outputTemplate : undefined;
      const rendered = template ? renderRecipeTemplate(template, model) : undefined;

      const inferredKey = generated.inferredTarget?.key;
      const inferredLine =
        typeof lineHint === "number"
          ? lineHint
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
                  "Use probe_target_infer class_methods/ranked_candidates to select a runtime-resolvable line and rerun probe_recipe_create.",
              },
            ],
            nextAction:
              "Strict line target is not runtime-resolvable for current JVM/source alignment. Select a validated runtime line via probe_target_infer and rerun probe_recipe_create.",
            nextActionCode: "select_resolvable_line",
            failurePhase: "target_inference" as const,
            failureReasonCode: "runtime_line_unresolved",
            reasonCode: "runtime_line_unresolved",
            failedStep: "line_validation",
            reasonMeta: {
              failedStep: "line_validation",
              classHint,
              methodHint,
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
                classHint,
                methodHint,
                lineHint,
                selectedMode: normalizedGenerated.selectedMode,
              },
              RECIPE_REASON_META_KEYS,
            )
          : undefined;

      const structuredContent = {
        projectRoot,
        hints: {
          classHint,
          methodHint,
          lineHint,
          mappingsBaseUrl,
          discoveryPreference,
          additionalSourceRoots:
            additionalRoots.normalizedAdditionalSourceRoots.length > 0
              ? additionalRoots.normalizedAdditionalSourceRoots
              : undefined,
          apiBasePath,
          actuationEnabled,
          actuationReturnBoolean,
          actuationActuatorId,
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
    },
  );
}

