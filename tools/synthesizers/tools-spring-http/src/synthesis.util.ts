import type { SynthesizerFailure } from "@/models/synthesis/synthesizer_failure.model";
import type {
  RuntimeMappingsResolveResult,
} from "@/models/synthesis/runtime_mappings.model";
import type { SynthesizerInput } from "@/models/synthesis/synthesizer_input.model";
import type {
  JvmAstRequestMappingFailure,
  JvmAstRequestMappingResult,
} from "@/models/synthesis/request_mapping_ast.model";
import type { SynthesizerOutput } from "@/models/synthesis/synthesizer_output.model";
import { resolveRequestMappingAst } from "@/lib/request_mapping_ast_resolver";
import { resolveRequestMappingFromRuntime } from "@/lib/request_mapping_runtime_resolver";
import {
  resolveGatewayRouteConfig,
  type GatewayRouteConfigResolveResult,
} from "@tools-spring-http/gateway_route_config.util";
import { SPRING_FAILURE_CODES } from "@tools-spring-http/failure_codes.util";

export type SynthesizeSpringRecipeDeps = {
  resolveRequestMappingFn?: (input: {
    projectRootAbs: string;
    searchRootsAbs?: string[];
    classHint: string;
    methodHint: string;
    lineHint?: number;
    inferredTargetFileAbs?: string;
  }) => Promise<JvmAstRequestMappingResult>;
  resolveRuntimeMappingsFn?: (input: {
    mappingsBaseUrl: string;
    classHint: string;
    methodHint: string;
    authToken?: string;
  }) => Promise<RuntimeMappingsResolveResult>;
  resolveGatewayRouteConfigFn?: (input: {
    projectRootAbs: string;
  }) => Promise<GatewayRouteConfigResolveResult>;
};

function isGatewayEntrypointTarget(input: SynthesizerInput): boolean {
  const method = input.methodHint.trim();
  const className = input.classHint.trim();
  if (method !== "main") return false;
  return /(?:^|[.$])[A-Za-z0-9_$]*Application$/.test(className);
}

function readResolverContextPathHint(
  extensions: Record<string, unknown> | undefined,
): string | undefined {
  if (!extensions) return undefined;
  for (const key of ["contextPathHint", "apiBasePath", "contextPath"]) {
    const value = extensions[key];
    if (typeof value === "string" && value.trim().length > 0) return value.trim();
  }
  return undefined;
}

function mapResolverFailureToSynthFailure(
  failure: JvmAstRequestMappingFailure,
  input: SynthesizerInput,
): SynthesizerFailure {
  if (
    failure.reasonCode === "ast_resolver_unavailable" ||
    failure.reasonCode === "project_root_invalid" ||
    failure.reasonCode === "target_type_not_found" ||
    failure.reasonCode === "target_type_ambiguous" ||
    failure.reasonCode === "target_method_not_found" ||
    failure.reasonCode === "mapper_plugin_unavailable"
  ) {
    return {
      status: "report",
      reasonCode: failure.reasonCode,
      failedStep: failure.failedStep,
      nextAction: failure.nextAction,
      evidence: failure.evidence,
      attemptedStrategies: failure.attemptedStrategies,
      synthesizerUsed: "spring",
    };
  }

  return {
    status: "report",
    reasonCode: SPRING_FAILURE_CODES.ENTRYPOINT_NOT_PROVEN,
    failedStep: "spring_entrypoint_resolution",
    nextAction:
      "Spring entrypoint could not be proven from AST-backed request mapping resolution. Provide tighter classHint/methodHint/lineHint and rerun route_synthesis with action=create_recipe.",
    evidence: [
      `classHint=${input.classHint}`,
      `methodHint=${input.methodHint}`,
      `lineHint=${typeof input.lineHint === "number" ? String(input.lineHint) : "(none)"}`,
      ...failure.evidence,
    ],
    attemptedStrategies: failure.attemptedStrategies,
    synthesizerUsed: "spring",
  };
}

export async function synthesizeSpringRecipe(
  input: SynthesizerInput,
  deps: SynthesizeSpringRecipeDeps = {},
): Promise<SynthesizerOutput | SynthesizerFailure> {
  const resolveRequestMappingFn = deps.resolveRequestMappingFn ?? resolveRequestMappingAst;
  const resolveRuntimeMappingsFn =
    deps.resolveRuntimeMappingsFn ?? resolveRequestMappingFromRuntime;
  const resolveGatewayRouteConfigFn =
    deps.resolveGatewayRouteConfigFn ?? resolveGatewayRouteConfig;
  const runtimeDiscoveryPreference = input.discoveryPreference ?? "static_only";
  let runtimeFailure:
    | { reasonCode: string; failedStep: string; evidence: string[]; attemptedStrategies: string[] }
    | undefined;

  if (runtimeDiscoveryPreference === "runtime_first" || runtimeDiscoveryPreference === "runtime_only") {
    if (!input.mappingsBaseUrl || input.mappingsBaseUrl.trim().length === 0) {
      if (runtimeDiscoveryPreference === "runtime_only") {
        return {
          status: "report",
          reasonCode: "runtime_mappings_input_required",
          failedStep: "runtime_mapping_configuration",
          nextAction:
            "Provide mappingsBaseUrl (for example http://127.0.0.1:8080/actuator/mappings) and rerun route_synthesis with action=create_recipe.",
          evidence: ["mappingsBaseUrl=(missing)"],
          attemptedStrategies: ["spring_runtime_actuator_mappings"],
          synthesizerUsed: "spring",
        };
      }
    } else {
      const runtimeResolved = await resolveRuntimeMappingsFn({
        mappingsBaseUrl: input.mappingsBaseUrl,
        classHint: input.classHint,
        methodHint: input.methodHint,
        ...(input.authToken ? { authToken: input.authToken } : {}),
      });

      if (runtimeResolved.status === "ok") {
        return {
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: runtimeResolved.requestCandidate,
          trigger: {
            kind: "http",
            method: runtimeResolved.requestCandidate.method,
            path: runtimeResolved.requestCandidate.path,
            queryTemplate: runtimeResolved.requestCandidate.queryTemplate,
            fullUrlHint: runtimeResolved.requestCandidate.fullUrlHint,
            ...(runtimeResolved.requestCandidate.bodyTemplate
              ? { bodyTemplate: runtimeResolved.requestCandidate.bodyTemplate }
              : {}),
            headers: {},
            ...(runtimeResolved.requestCandidate.bodyTemplate ? { contentType: "application/json" } : {}),
          },
          requestSource: "spring_mvc",
          matchedRootAbs: input.rootAbs,
          evidence: [
            "request_source=spring_mvc",
            ...runtimeResolved.evidence,
          ],
          attemptedStrategies: runtimeResolved.attemptedStrategies,
        };
      }

      if (runtimeDiscoveryPreference === "runtime_only") {
        return {
          status: "report",
          reasonCode: runtimeResolved.reasonCode,
          failedStep: runtimeResolved.failedStep,
          nextAction: runtimeResolved.nextAction,
          evidence: runtimeResolved.evidence,
          attemptedStrategies: runtimeResolved.attemptedStrategies,
          synthesizerUsed: "spring",
        };
      }

      runtimeFailure = {
        reasonCode: runtimeResolved.reasonCode,
        failedStep: runtimeResolved.failedStep,
        evidence: runtimeResolved.evidence,
        attemptedStrategies: runtimeResolved.attemptedStrategies,
      };
    }
  }

  const resolved = await resolveRequestMappingFn({
    projectRootAbs: input.rootAbs,
    ...(input.searchRootsAbs.length > 0 ? { searchRootsAbs: input.searchRootsAbs } : {}),
    classHint: input.classHint,
    methodHint: input.methodHint,
    ...(typeof input.lineHint === "number" ? { lineHint: input.lineHint } : {}),
    ...(input.inferredTargetFileAbs ? { inferredTargetFileAbs: input.inferredTargetFileAbs } : {}),
  });

  if (resolved.status !== "ok") {
    if (isGatewayEntrypointTarget(input)) {
      const gatewayResolved = await resolveGatewayRouteConfigFn({
        projectRootAbs: input.rootAbs,
      });
      if (gatewayResolved.status === "ok") {
        return {
          status: "recipe",
          synthesizerUsed: "spring",
          framework: "spring",
          requestCandidate: gatewayResolved.requestCandidate,
          trigger: {
            kind: "http",
            method: gatewayResolved.requestCandidate.method,
            path: gatewayResolved.requestCandidate.path,
            queryTemplate: gatewayResolved.requestCandidate.queryTemplate,
            fullUrlHint: gatewayResolved.requestCandidate.fullUrlHint,
            headers: {},
          },
          requestSource: "spring_mvc",
          matchedRootAbs: input.rootAbs,
          evidence: [
            `request_source=spring_gateway_route_config`,
            ...gatewayResolved.evidence,
            ...resolved.evidence,
            ...(runtimeFailure
              ? [`runtime_mappings_fallback_reason=${runtimeFailure.reasonCode}`]
              : []),
          ],
          attemptedStrategies: [
            ...(runtimeFailure?.attemptedStrategies ?? []),
            ...gatewayResolved.attemptedStrategies,
            ...resolved.attemptedStrategies,
          ],
        };
      }
      return {
        status: "report",
        reasonCode: gatewayResolved.reasonCode,
        failedStep: gatewayResolved.failedStep,
        nextAction: gatewayResolved.nextAction,
        evidence: [
          ...gatewayResolved.evidence,
          ...resolved.evidence,
          ...(runtimeFailure
            ? [`runtime_mappings_fallback_reason=${runtimeFailure.reasonCode}`]
            : []),
        ],
        attemptedStrategies: [
          ...(runtimeFailure?.attemptedStrategies ?? []),
          ...gatewayResolved.attemptedStrategies,
          ...resolved.attemptedStrategies,
        ],
        synthesizerUsed: "spring",
      };
    }
    return mapResolverFailureToSynthFailure(resolved, input);
  }
  const contextPathHint = readResolverContextPathHint(resolved.extensions);

  const out: SynthesizerOutput = {
    status: "recipe",
    synthesizerUsed: "spring",
    framework: "spring",
    requestCandidate: resolved.requestCandidate,
    trigger: {
      kind: "http",
      method: resolved.requestCandidate.method,
      path: resolved.requestCandidate.path,
      queryTemplate: resolved.requestCandidate.queryTemplate,
      fullUrlHint: resolved.requestCandidate.fullUrlHint,
      ...(resolved.requestCandidate.bodyTemplate
        ? { bodyTemplate: resolved.requestCandidate.bodyTemplate }
        : {}),
      headers: {},
      ...(resolved.requestCandidate.bodyTemplate ? { contentType: "application/json" } : {}),
    },
    requestSource: resolved.requestSource,
    matchedControllerFile: resolved.matchedTypeFile,
    matchedRootAbs: resolved.matchedRootAbs,
    evidence: [
      `request_source=${resolved.requestSource}`,
      `controller_file=${resolved.matchedTypeFile}`,
      `ast_framework=${resolved.framework}`,
      ...resolved.evidence,
      ...(runtimeFailure
        ? [`runtime_mappings_fallback_reason=${runtimeFailure.reasonCode}`]
        : []),
      ...(contextPathHint ? [`ast_context_path_hint=${contextPathHint}`] : []),
    ],
    attemptedStrategies: [
      ...(runtimeFailure?.attemptedStrategies ?? []),
      ...resolved.attemptedStrategies,
    ],
  };
  return out;
}
