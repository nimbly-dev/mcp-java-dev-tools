import * as path from "node:path";

import { deriveNextActionCode } from "@tools-core/failure_diagnostics";
import { validateProjectRootAbs } from "@tools-core/project_root_validate";
import { resolveSpringHandlerInventory } from "@tools-core/spring_handler_inventory_resolver";
import type { JvmAstHandlerInventoryEntry } from "@tools-registry/models/synthesis/request_mapping_ast.model";

import type { RouteSynthesisHandlerDeps } from "../models/route_synthesis.model";
import { resolveAdditionalSourceRoots } from "../support/source_roots_resolve";
import { resolveProbeBaseUrl } from "../support/probe_base_url_resolve";
import {
  RuntimeProbeUnreachableError,
  selectTargetRuntimeLine,
} from "../support/target_infer_runtime";

type HandlerDiscoveryInput = {
  projectRootAbs?: string;
  classHint?: string;
  additionalSourceRoots?: string[];
  probeId?: string;
  probeBaseUrl?: string;
};

type HandlerLineStatus = {
  firstExecutableLine: number | null;
  lineSelectionStatus: "validated" | "unresolved";
  lineSelectionSource?: "runtime_probe_validation";
  lineSelectionReasonCode?: string;
  nextActionCode?: string;
  nextAction?: string;
};

function toResponse(structuredContent: Record<string, unknown>) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

function isExactFqcn(value: string | undefined): value is string {
  return typeof value === "string" && /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$/.test(value);
}

function unresolvedLine(reasonCode: string, nextAction: string): HandlerLineStatus {
  const nextActionCode = deriveNextActionCode(reasonCode);
  return {
    firstExecutableLine: null,
    lineSelectionStatus: "unresolved",
    lineSelectionReasonCode: reasonCode,
    ...(nextActionCode ? { nextActionCode } : {}),
    nextAction,
  };
}

async function validateHandlerLine(args: {
  handler: JvmAstHandlerInventoryEntry;
  controllerFqcn: string;
  deps: RouteSynthesisHandlerDeps;
  probeBaseUrl: string;
}): Promise<HandlerLineStatus> {
  const selected = await selectTargetRuntimeLine({
    probeKey: `${args.handler.runtimeClassFqcn || args.controllerFqcn}#${args.handler.methodName}`,
    probeBaseUrl: args.probeBaseUrl,
    startLine: args.handler.declarationLine,
    endLine: args.handler.endLine,
    config: args.deps.config,
  });
  if (selected.lineSelectionStatus === "unresolved") {
    return unresolvedLine(
      "runtime_line_unresolved",
      "Verify runtime/source alignment and rerun discover_handlers with a reachable Probe selector.",
    );
  }
  return selected;
}

export async function runDiscoverHandlers(
  input: Record<string, unknown>,
  deps: RouteSynthesisHandlerDeps,
) {
  const requested = input as HandlerDiscoveryInput;
  if (!isExactFqcn(requested.classHint)) {
    return toResponse({
      resultType: "report",
      status: "blocked_invalid",
      reasonCode: "class_hint_not_fqcn",
      nextActionCode: "provide_class_fqcn",
      failedStep: "input_validation",
      nextAction: "Provide classHint as an exact controller FQCN and rerun discover_handlers.",
    });
  }

  const root = await validateProjectRootAbs(requested.projectRootAbs);
  if (!root.ok) {
    return toResponse({
      resultType: "report",
      status: root.status,
      reasonCode: root.status,
      nextActionCode: deriveNextActionCode(root.status),
      failedStep: "project_root_validation",
      reason: root.reason,
      nextAction: root.nextAction,
    });
  }
  if (!deps.config.workspaceRootAbs) {
    return toResponse({
      resultType: "report",
      status: "workspace_context_missing",
      reasonCode: "workspace_context_missing",
      nextActionCode: "bind_workspace_root",
      failedStep: "workspace_resolution",
      nextAction: "Bind an MCP workspace root and rerun discover_handlers.",
    });
  }

  const additionalRoots = await resolveAdditionalSourceRoots({
    workspaceRootAbs: deps.config.workspaceRootAbs,
    ...(Array.isArray(requested.additionalSourceRoots)
      ? { additionalSourceRoots: requested.additionalSourceRoots }
      : {}),
  });
  if (!additionalRoots.ok) {
    return toResponse({
      resultType: "report",
      status: "project_selector_invalid",
      reasonCode: additionalRoots.reasonCode,
      nextActionCode: deriveNextActionCode(additionalRoots.reasonCode),
      failedStep: additionalRoots.failedStep,
      reason: additionalRoots.reason,
      nextAction: additionalRoots.nextAction,
      evidence: additionalRoots.evidence,
    });
  }

  const discovered = await resolveSpringHandlerInventory({
    projectRootAbs: root.projectRootAbs,
    classHint: requested.classHint,
    ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
      ? { searchRootsAbs: additionalRoots.normalizedAdditionalSourceRoots }
      : {}),
  });
  if (discovered.status !== "ok") {
    return toResponse({
      resultType: "report",
      status: "blocked",
      reasonCode: discovered.reasonCode,
      nextActionCode: deriveNextActionCode(discovered.reasonCode),
      failedStep: discovered.failedStep,
      nextAction: discovered.nextAction,
      evidence: discovered.evidence,
      attemptedStrategies: discovered.attemptedStrategies,
    });
  }

  const runtimeRequested = Boolean(requested.probeId?.trim() || requested.probeBaseUrl?.trim());
  const runtimeUnavailable = unresolvedLine(
    "runtime_validation_not_requested",
    "Provide probeId or probeBaseUrl to validate Strict Line Keys for discovered handlers.",
  );
  let activeProbeBaseUrl: string | undefined;
  if (runtimeRequested) {
    const probe = resolveProbeBaseUrl({
      defaultProbeBaseUrl: deps.config.probeBaseUrl,
      ...(deps.config.probeRegistry ? { probeRegistry: deps.config.probeRegistry } : {}),
      ...(requested.probeId ? { probeId: requested.probeId } : {}),
      ...(requested.probeBaseUrl ? { probeBaseUrl: requested.probeBaseUrl } : {}),
    });
    if (!probe.ok) {
      return toResponse({
        resultType: "report",
        status: "blocked_invalid",
        reasonCode: probe.reasonCode,
        nextActionCode: deriveNextActionCode(probe.reasonCode),
        failedStep: "input_validation",
        reason: probe.reason,
        nextAction:
          "Provide a configured probeId or valid probeBaseUrl and rerun discover_handlers.",
      });
    }
    activeProbeBaseUrl = probe.probeBaseUrl;
  }

  let runtimeFailure: HandlerLineStatus | undefined;
  const handlers = [] as Array<Record<string, unknown>>;
  for (const handler of discovered.handlers) {
    let lineStatus = runtimeFailure ?? runtimeUnavailable;
    if (activeProbeBaseUrl && !runtimeFailure) {
      try {
        lineStatus = await validateHandlerLine({
          handler,
          controllerFqcn: discovered.controllerFqcn,
          deps,
          probeBaseUrl: activeProbeBaseUrl,
        });
      } catch (err) {
        if (!(err instanceof RuntimeProbeUnreachableError)) throw err;
        runtimeFailure = unresolvedLine(
          "runtime_unreachable",
          "Verify Probe reachability, then rerun discover_handlers with the same controller FQCN.",
        );
        lineStatus = runtimeFailure;
      }
    }
    const strictLineKey =
      lineStatus.lineSelectionStatus === "validated" && lineStatus.firstExecutableLine !== null
        ? `${handler.runtimeClassFqcn || discovered.controllerFqcn}#${handler.methodName}:${lineStatus.firstExecutableLine}`
        : undefined;
    handlers.push({
      ...handler,
      ...lineStatus,
      ...(strictLineKey ? { strictLineKey } : {}),
    });
  }

  const allValidated = handlers.every((handler) => handler.lineSelectionStatus === "validated");
  return toResponse({
    resultType: "handler_inventory",
    status: allValidated ? "ready" : "partial",
    framework: discovered.framework,
    controllerFqcn: discovered.controllerFqcn,
    projectRoot: root.projectRootAbs,
    hints: {
      projectRootAbs: root.projectRootAbs,
      classHint: requested.classHint,
      ...(requested.probeId ? { probeId: requested.probeId } : {}),
    },
    ...(additionalRoots.normalizedAdditionalSourceRoots.length > 0
      ? { additionalSourceRoots: additionalRoots.normalizedAdditionalSourceRoots }
      : {}),
    handlers,
    ...(allValidated ? {} : { reasonCode: "handler_line_validation_partial" }),
    evidence: discovered.evidence,
    attemptedStrategies: [
      ...discovered.attemptedStrategies,
      ...(runtimeRequested
        ? ["runtime_line_validation"]
        : ["runtime_line_validation_not_requested"]),
    ],
    matchedTypeFile: path.relative(root.projectRootAbs, discovered.matchedTypeFile),
  });
}
