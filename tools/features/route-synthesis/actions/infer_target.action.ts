import * as path from "node:path";

import type { RouteSynthesisTargetInferenceDeps } from "@tools-feature-route-synthesis";
import { clampInt } from "@tools-core/safety";
import { deriveNextActionCode, normalizeReasonMeta } from "@tools-core/failure_diagnostics";
import { validateProjectRootAbs } from "@tools-core/project_root_validate";
import { resolveAdditionalSourceRoots } from "../support/source_roots_resolve";
import { resolveProbeBaseUrl } from "../support/probe_base_url_resolve";
import {
  RuntimeProbeUnreachableError,
  runtimeUnavailableResponse,
  selectTargetRuntimeLine,
  TARGET_INFER_REASON_META_KEYS,
} from "../support/target_infer_runtime";
import { discoverClassMethods, inferTargets } from "../shared/target_inference";

export async function runTargetInfer(
  input: Record<string, unknown>,
  deps: RouteSynthesisTargetInferenceDeps,
): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  const {
    projectRootAbs,
    discoveryMode,
    classHint,
    methodHint,
    lineHint,
    maxCandidates,
    additionalSourceRoots,
    probeId,
    probeBaseUrl,
  } = input as {
    projectRootAbs: string;
    discoveryMode?: "ranked_candidates" | "class_methods";
    classHint?: string;
    methodHint?: string;
    lineHint?: number;
    maxCandidates?: number;
    additionalSourceRoots?: string[];
    probeId?: string;
    probeBaseUrl?: string;
  };
  const probeResolve = resolveProbeBaseUrl({
    defaultProbeBaseUrl: deps.config.probeBaseUrl,
    ...(deps.config.probeRegistry ? { probeRegistry: deps.config.probeRegistry } : {}),
    ...(typeof probeId === "string" ? { probeId } : {}),
    ...(typeof probeBaseUrl === "string" ? { probeBaseUrl } : {}),
  });
  if (!probeResolve.ok) {
    const structuredContent = {
      resultType: "report",
      status: "blocked_invalid",
      reasonCode: probeResolve.reasonCode,
      nextActionCode: deriveNextActionCode(probeResolve.reasonCode),
      failedStep: "input_validation",
      reason: probeResolve.reason,
      nextAction:
        "Provide a valid probeId from artifact_management (artifactType=probe_config, action=read) or explicit probeBaseUrl and rerun route_synthesis.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }
  const activeProbeBaseUrl = probeResolve.probeBaseUrl;
  const selectedDiscoveryMode = discoveryMode ?? "ranked_candidates";
  const validated = await validateProjectRootAbs(projectRootAbs);
  if (!validated.ok) {
    const reasonCode = validated.status;
    const structuredContent = {
      resultType: "report",
      status: reasonCode,
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "project_root_validation",
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "project_root_validation",
          classHint,
          methodHint,
          lineHint,
          discoveryMode: discoveryMode ?? "ranked_candidates",
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      reason: validated.reason,
      ...(validated.value ? { projectRootAbs: validated.value } : {}),
      nextAction: validated.nextAction,
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const rootAbs = validated.projectRootAbs;
  const workspaceRootAbs = deps.config.workspaceRootAbs;
  if (!workspaceRootAbs) {
    const structuredContent = {
      resultType: "report",
      status: "workspace_context_missing",
      reasonCode: "workspace_context_missing",
      nextActionCode: "bind_workspace_root",
      failedStep: "workspace_resolution",
      reason: "No MCP workspace root is bound to this session.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }
  const additionalRoots = await resolveAdditionalSourceRoots({
    workspaceRootAbs,
    ...(Array.isArray(additionalSourceRoots) &&
    additionalSourceRoots.every((value) => typeof value === "string")
      ? { additionalSourceRoots: additionalSourceRoots as string[] }
      : {}),
  });
  if (!additionalRoots.ok) {
    const reasonCode = additionalRoots.reasonCode;
    const structuredContent = {
      resultType: "report",
      status: "project_selector_invalid",
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: additionalRoots.failedStep,
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint, additionalSourceRoots },
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: additionalRoots.failedStep,
          classHint,
          methodHint,
          lineHint,
          discoveryMode: selectedDiscoveryMode,
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      reason: additionalRoots.reason,
      nextAction: additionalRoots.nextAction,
      evidence: additionalRoots.evidence,
      attemptedStrategies: ["additional_source_roots_validation"],
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }
  if (selectedDiscoveryMode === "class_methods") {
    const classHintTrimmed = classHint?.trim();
    if (!classHintTrimmed) {
      const reasonCode = "class_hint_required";
      const structuredContent = {
        resultType: "report",
        status: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        failedStep: "input_validation",
        projectRoot: rootAbs,
        reasonMeta: normalizeReasonMeta(
          {
            failedStep: "input_validation",
            classHint,
            methodHint,
            lineHint,
            discoveryMode: selectedDiscoveryMode,
          },
          TARGET_INFER_REASON_META_KEYS,
        ),
        nextAction: "Provide classHint and rerun route_synthesis with action=class_methods.",
      };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    }

    const discovered = await discoverClassMethods({
      rootAbs,
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalRootsAbs: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      classHint: classHintTrimmed,
    });
    const chosenMatches = discovered.classes;

    if (chosenMatches.length === 0) {
      const reasonCode = "class_not_found";
      const structuredContent = {
        resultType: "class_methods",
        status: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        failedStep: "target_inference",
        projectRoot: rootAbs,
        hints: { projectRootAbs: rootAbs, classHint },
        ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
          ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
          : {}),
        scannedJavaFiles: discovered.scannedJavaFiles,
        reasonMeta: normalizeReasonMeta(
          {
            failedStep: "target_inference",
            classHint,
            discoveryMode: selectedDiscoveryMode,
          },
          TARGET_INFER_REASON_META_KEYS,
        ),
        nextAction:
          "Refine classHint (prefer exact class name or fully qualified class name) and rerun route_synthesis with action=class_methods.",
      };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    }

    const matches = chosenMatches.map((match) => ({
      className: match.className,
      ...(match.fqcn ? { fqcn: match.fqcn } : {}),
      file: path.relative(rootAbs, match.file) || match.file,
    }));

    if (matches.length > 1) {
      const reasonCode = "class_ambiguous";
      const structuredContent = {
        resultType: "disambiguation",
        status: reasonCode,
        reasonCode,
        nextActionCode: deriveNextActionCode(reasonCode),
        failedStep: "target_selection",
        projectRoot: rootAbs,
        hints: { projectRootAbs: rootAbs, classHint },
        ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
          ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
          : {}),
        scannedJavaFiles: discovered.scannedJavaFiles,
        matches,
        reasonMeta: normalizeReasonMeta(
          {
            failedStep: "target_selection",
            classHint,
            discoveryMode: selectedDiscoveryMode,
            candidateCount: matches.length,
          },
          TARGET_INFER_REASON_META_KEYS,
        ),
        nextAction: "Refine classHint to exact FQCN to resolve a single class.",
      };
      return {
        content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
        structuredContent,
      };
    }

    const selected = chosenMatches[0]!;
    const validatedMethods: typeof selected.methods = [];
    try {
      for (const method of selected.methods) {
        const runtimeSelection = await selectTargetRuntimeLine({
          ...(method.probeKey ? { probeKey: method.probeKey } : {}),
          probeBaseUrl: activeProbeBaseUrl,
          startLine: method.startLine,
          endLine: method.endLine,
          config: deps.config,
        });
        validatedMethods.push({
          ...method,
          firstExecutableLine: runtimeSelection.firstExecutableLine,
          lineSelectionStatus: runtimeSelection.lineSelectionStatus,
          ...(runtimeSelection.lineSelectionSource
            ? { lineSelectionSource: runtimeSelection.lineSelectionSource }
            : {}),
        });
      }
    } catch (err) {
      if (err instanceof RuntimeProbeUnreachableError) {
        return runtimeUnavailableResponse({
          rootAbs,
          hints: { projectRootAbs: rootAbs, classHint, discoveryMode: selectedDiscoveryMode },
          reason: err.message,
        });
      }
      throw err;
    }

    const structuredContent = {
      resultType: "class_methods",
      status: "ok",
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint },
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      scannedJavaFiles: discovered.scannedJavaFiles,
      class: {
        className: selected.className,
        ...(selected.fqcn ? { fqcn: selected.fqcn } : {}),
        file: path.relative(rootAbs, selected.file) || selected.file,
      },
      methods: validatedMethods,
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  if (!classHint?.trim()) {
    const reasonCode = "class_hint_required";
    const structuredContent = {
      resultType: "report",
      status: reasonCode,
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "input_validation",
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "input_validation",
          classHint,
          methodHint,
          lineHint,
          discoveryMode: selectedDiscoveryMode,
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      reason: "ranked_candidates requires exact classHint for deterministic target selection.",
      nextAction:
        "Provide classHint as exact FQCN (preferred) or exact class name, then rerun route_synthesis with action=infer_target.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const inferred = await inferTargets({
    rootAbs,
    ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
      ? { additionalRootsAbs: additionalRoots.normalizedAdditionalSourceRoots }
      : {}),
    maxCandidates: clampInt(maxCandidates ?? 8, 1, 20),
    ...(classHint ? { classHint } : {}),
    ...(methodHint ? { methodHint } : {}),
    ...(typeof lineHint === "number" ? { lineHint } : {}),
  });

  if (inferred.candidates.length === 0) {
    const reasonCode = "target_not_found";
    const structuredContent = {
      resultType: "report",
      status: reasonCode,
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "target_inference",
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      scannedJavaFiles: inferred.scannedJavaFiles,
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "target_inference",
          classHint,
          methodHint,
          lineHint,
          discoveryMode: selectedDiscoveryMode,
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      nextAction:
        "Refine classHint/methodHint to exact runtime identifiers and rerun route_synthesis with action=infer_target.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const validatedCandidates = [] as typeof inferred.candidates;
  try {
    for (const candidate of inferred.candidates) {
      const runtimeSelection = await selectTargetRuntimeLine({
        ...(candidate.key ? { probeKey: candidate.key } : {}),
        probeBaseUrl: activeProbeBaseUrl,
        startLine:
          typeof candidate.declarationLine === "number"
            ? candidate.declarationLine
            : typeof candidate.line === "number"
              ? candidate.line
              : 1,
        endLine:
          typeof candidate.endLine === "number"
            ? candidate.endLine
            : typeof candidate.declarationLine === "number"
              ? candidate.declarationLine
              : typeof candidate.line === "number"
                ? candidate.line
                : 1,
        config: deps.config,
      });
      validatedCandidates.push({
        ...candidate,
        line: runtimeSelection.firstExecutableLine,
        firstExecutableLine: runtimeSelection.firstExecutableLine,
        lineSelectionStatus: runtimeSelection.lineSelectionStatus,
        ...(runtimeSelection.lineSelectionSource
          ? { lineSelectionSource: runtimeSelection.lineSelectionSource }
          : {}),
      });
    }
  } catch (err) {
    if (err instanceof RuntimeProbeUnreachableError) {
      return runtimeUnavailableResponse({
        rootAbs,
        hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
        reason: err.message,
      });
    }
    throw err;
  }

  const runtimeResolvedCandidates = validatedCandidates.filter(
    (candidate) =>
      candidate.lineSelectionStatus === "validated" && typeof candidate.line === "number",
  );
  if (runtimeResolvedCandidates.length === 0) {
    const reasonCode = "runtime_line_unresolved";
    const structuredContent = {
      resultType: "report",
      status: "target_not_found",
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "line_validation",
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      scannedJavaFiles: inferred.scannedJavaFiles,
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "line_validation",
          classHint,
          methodHint,
          lineHint,
          discoveryMode: selectedDiscoveryMode,
          candidateCount: validatedCandidates.length,
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      evidence: [
        `candidateCount=${validatedCandidates.length}`,
        `maxScanLines=${deps.config.probeLineSelectionMaxScanLines}`,
      ],
      attemptedStrategies: ["target_inference_exact_match", "runtime_line_validation"],
      nextAction:
        "No runtime-resolvable line was found for inferred candidates. Verify runtime/source alignment and rerun route_synthesis with action=infer_target.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const lineMatches =
    typeof lineHint === "number"
      ? runtimeResolvedCandidates.filter((candidate) => candidate.line === lineHint)
      : [];
  if (typeof lineHint === "number" && lineMatches.length === 0) {
    const reasonCode = "line_hint_not_resolvable";
    const structuredContent = {
      resultType: "report",
      status: "target_not_found",
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "line_validation",
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      scannedJavaFiles: inferred.scannedJavaFiles,
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "line_validation",
          classHint,
          methodHint,
          lineHint,
          discoveryMode: selectedDiscoveryMode,
          resolvedCandidateCount: runtimeResolvedCandidates.length,
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      evidence: [
        `lineHint=${lineHint}`,
        `resolvedCandidateCount=${runtimeResolvedCandidates.length}`,
      ],
      attemptedStrategies: ["target_inference_exact_match", "runtime_line_validation"],
      nextAction:
        "Provided lineHint is not runtime-resolvable for inferred candidates. Use class_methods output to select a validated line and rerun.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const selectedCandidates = typeof lineHint === "number" ? lineMatches : runtimeResolvedCandidates;

  if (selectedCandidates.length > 1) {
    const reasonCode = "target_ambiguous";
    const structuredContent = {
      resultType: "disambiguation",
      status: "target_ambiguous",
      reasonCode,
      nextActionCode: deriveNextActionCode(reasonCode),
      failedStep: "target_selection",
      projectRoot: rootAbs,
      hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
      ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
        ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
        : {}),
      scannedJavaFiles: inferred.scannedJavaFiles,
      reasonMeta: normalizeReasonMeta(
        {
          failedStep: "target_selection",
          classHint,
          methodHint,
          lineHint,
          discoveryMode: selectedDiscoveryMode,
          candidateCount: selectedCandidates.length,
        },
        TARGET_INFER_REASON_META_KEYS,
      ),
      matches: selectedCandidates.map((candidate) => ({
        ...candidate,
        file: path.relative(rootAbs, candidate.file) || candidate.file,
      })),
      nextAction:
        "Refine classHint to exact FQCN and methodHint to exact method name (add lineHint only when strict line disambiguation is known), then rerun route_synthesis with action=infer_target.",
    };
    return {
      content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
      structuredContent,
    };
  }

  const structuredContent = {
    resultType: "ranked_candidates",
    status: "ok",
    projectRoot: rootAbs,
    hints: { projectRootAbs: rootAbs, classHint, methodHint, lineHint },
    ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
      ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
      : {}),
    scannedJavaFiles: inferred.scannedJavaFiles,
    candidates: selectedCandidates.map((candidate) => ({
      ...candidate,
      file: path.relative(rootAbs, candidate.file) || candidate.file,
    })),
  };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}
