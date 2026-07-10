import type {
  RuntimeMappingsResolveFailure,
  RuntimeMappingsResolveInput,
  RuntimeMappingsResolveResult,
} from "@tools-registry/models/synthesis/runtime_mappings.model";

const RUNTIME_ATTEMPTED_STRATEGY = "spring_runtime_actuator_mappings";

type RuntimeResolvedRoute = {
  httpMethod: string;
  path: string;
  handlerClass: string;
  handlerMethod: string;
};

type RuntimeHandlerRef = {
  className?: string;
  methodName?: string;
};

function normalizeMappingsUrl(raw: string): string {
  const parsed = new URL(raw);
  const normalizedPath = parsed.pathname.trim();
  if (normalizedPath === "" || normalizedPath === "/") {
    parsed.pathname = "/actuator/mappings";
    return parsed.toString();
  }

  const withoutTrailingSlash =
    normalizedPath.length > 1 ? normalizedPath.replace(/\/+$/, "") : normalizedPath;
  if (withoutTrailingSlash === "/actuator") {
    parsed.pathname = "/actuator/mappings";
    return parsed.toString();
  }

  if (withoutTrailingSlash === "/actuator/mappings") {
    parsed.pathname = "/actuator/mappings";
    return parsed.toString();
  }

  return parsed.toString();
}

function failure(args: {
  reasonCode: RuntimeMappingsResolveFailure["reasonCode"];
  failedStep: string;
  nextAction: string;
  evidence: string[];
}): RuntimeMappingsResolveResult {
  return {
    status: "report",
    reasonCode: args.reasonCode,
    failedStep: args.failedStep,
    nextAction: args.nextAction,
    evidence: args.evidence,
    attemptedStrategies: [RUNTIME_ATTEMPTED_STRATEGY],
  };
}

function parseHandlerRef(node: Record<string, unknown>): RuntimeHandlerRef {
  const details = isRecord(node.details) ? node.details : undefined;
  const handlerMethod = details && isRecord(details.handlerMethod) ? details.handlerMethod : undefined;
  const className =
    typeof handlerMethod?.className === "string" ? handlerMethod.className : undefined;
  const methodName = typeof handlerMethod?.name === "string" ? handlerMethod.name : undefined;

  if (className && methodName) {
    return { className, methodName };
  }

  const handler = typeof node.handler === "string" ? node.handler : undefined;
  if (!handler) {
    return {};
  }
  const match = handler.match(/([A-Za-z_$][\w.$]*)#([A-Za-z_$][\w$]*)/);
  if (!match) {
    return {};
  }
  const parsedClassName = match[1];
  const parsedMethodName = match[2];
  return {
    ...(parsedClassName ? { className: parsedClassName } : {}),
    ...(parsedMethodName ? { methodName: parsedMethodName } : {}),
  };
}

function extractMethodsFromCondition(condition: Record<string, unknown>): string[] {
  const methods = condition.methods;
  if (!Array.isArray(methods)) {
    return [];
  }
  const out: string[] = [];
  for (const value of methods) {
    if (typeof value === "string" && value.trim().length > 0) {
      out.push(value.trim().toUpperCase());
      continue;
    }
    if (isRecord(value) && typeof value.name === "string" && value.name.trim().length > 0) {
      out.push(value.name.trim().toUpperCase());
    }
  }
  return unique(out);
}

function extractPatternsFromCondition(condition: Record<string, unknown>): string[] {
  const patterns = condition.patterns;
  if (!Array.isArray(patterns)) {
    return [];
  }
  const out = patterns
    .filter((value): value is string => typeof value === "string")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  return unique(out.map(normalizePath));
}

function extractFromPredicate(predicate: string): { methods: string[]; patterns: string[] } {
  const match = predicate.match(/\{([^\[]+)\[([^\]]+)\]/);
  if (!match) {
    return { methods: [], patterns: [] };
  }

  const methodTokens = (match[1] ?? "")
    .split(",")
    .map((token) => token.trim().toUpperCase())
    .filter((token) => /^[A-Z]+$/.test(token));

  const patternTokens = (match[2] ?? "")
    .split(",")
    .map((token) => token.trim())
    .filter((token) => token.startsWith("/"));

  return {
    methods: unique(methodTokens),
    patterns: unique(patternTokens.map(normalizePath)),
  };
}

function normalizePath(pathValue: string): string {
  const trimmed = pathValue.trim();
  if (!trimmed) return "/";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function unique(values: string[]): string[] {
  return values.filter((value, index, arr) => arr.indexOf(value) === index);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function collectRoutes(node: unknown, out: RuntimeResolvedRoute[]): void {
  if (Array.isArray(node)) {
    for (const item of node) {
      collectRoutes(item, out);
    }
    return;
  }
  if (!isRecord(node)) {
    return;
  }

  const handlerRef = parseHandlerRef(node);
  const className = handlerRef.className;
  const methodName = handlerRef.methodName;
  if (className && methodName) {
    const details = isRecord(node.details) ? node.details : undefined;
    const requestMappingConditions =
      details && isRecord(details.requestMappingConditions) ? details.requestMappingConditions : undefined;

    const conditionMethods = requestMappingConditions
      ? extractMethodsFromCondition(requestMappingConditions)
      : [];
    const conditionPatterns = requestMappingConditions
      ? extractPatternsFromCondition(requestMappingConditions)
      : [];

    const predicate = typeof node.predicate === "string" ? node.predicate : "";
    const predicateExtracted = predicate ? extractFromPredicate(predicate) : { methods: [], patterns: [] };

    const methods = conditionMethods.length > 0 ? conditionMethods : predicateExtracted.methods;
    const patterns = conditionPatterns.length > 0 ? conditionPatterns : predicateExtracted.patterns;

    if (methods.length > 0 && patterns.length > 0) {
      for (const httpMethod of methods) {
        for (const path of patterns) {
          out.push({
            httpMethod,
            path,
            handlerClass: className,
            handlerMethod: methodName,
          });
        }
      }
    }
  }

  for (const value of Object.values(node)) {
    collectRoutes(value, out);
  }
}

export async function resolveRequestMappingFromRuntime(
  input: RuntimeMappingsResolveInput,
): Promise<RuntimeMappingsResolveResult> {
  let endpointUrl: string;
  try {
    endpointUrl = normalizeMappingsUrl(input.mappingsBaseUrl);
  } catch {
    return failure({
      reasonCode: "runtime_mappings_input_required",
      failedStep: "runtime_mapping_configuration",
      nextAction: "Provide a valid absolute mappingsBaseUrl and rerun route_synthesis with action=create_recipe.",
      evidence: [`mappingsBaseUrl=${input.mappingsBaseUrl}`],
    });
  }

  let response: Response;
  try {
    const headers: Record<string, string> = {
      accept: "application/json",
    };
    if (input.authToken && input.authToken.trim().length > 0) {
      headers.authorization = `Bearer ${input.authToken.trim()}`;
    }
    response = await fetch(endpointUrl, {
      method: "GET",
      headers,
    });
  } catch (error) {
    return failure({
      reasonCode: "runtime_mappings_unreachable",
      failedStep: "runtime_mapping_fetch",
      nextAction:
        "Ensure actuator mappings endpoint is reachable (for example /actuator/mappings) and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `mappingsBaseUrl=${endpointUrl}`,
        `fetchError=${error instanceof Error ? error.message : String(error)}`,
      ],
    });
  }

  if (response.status === 401 || response.status === 403) {
    return failure({
      reasonCode: "runtime_mappings_unauthorized",
      failedStep: "runtime_mapping_fetch",
      nextAction:
        "Authorize access to the actuator mappings endpoint and rerun route_synthesis with action=create_recipe.",
      evidence: [`mappingsBaseUrl=${endpointUrl}`, `httpStatus=${response.status}`],
    });
  }

  if (!response.ok) {
    return failure({
      reasonCode: "runtime_mappings_unreachable",
      failedStep: "runtime_mapping_fetch",
      nextAction:
        "Ensure actuator mappings endpoint is reachable (for example /actuator/mappings) and rerun route_synthesis with action=create_recipe.",
      evidence: [`mappingsBaseUrl=${endpointUrl}`, `httpStatus=${response.status}`],
    });
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch (error) {
    return failure({
      reasonCode: "runtime_mappings_invalid_payload",
      failedStep: "runtime_mapping_parse",
      nextAction:
        "Return Spring actuator mappings JSON payload from mappingsBaseUrl and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `mappingsBaseUrl=${endpointUrl}`,
        `parseError=${error instanceof Error ? error.message : String(error)}`,
      ],
    });
  }

  const routes: RuntimeResolvedRoute[] = [];
  collectRoutes(payload, routes);

  const matched = routes.filter(
    (route) =>
      route.handlerClass === input.classHint && route.handlerMethod === input.methodHint,
  );
  const uniqueRoutes = unique(
    matched.map((route) => `${route.httpMethod} ${route.path}`),
  ).map((entry) => {
    const [httpMethod, ...pathParts] = entry.split(" ");
    return { httpMethod: httpMethod ?? "", path: pathParts.join(" ") };
  });

  if (uniqueRoutes.length === 0) {
    return failure({
      reasonCode: "runtime_mapping_not_found",
      failedStep: "runtime_mapping_match",
      nextAction:
        "Refine classHint/methodHint to exact runtime handler identifiers or use static discovery and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `mappingsBaseUrl=${endpointUrl}`,
        `classHint=${input.classHint}`,
        `methodHint=${input.methodHint}`,
        `runtimeRouteCount=${routes.length}`,
      ],
    });
  }

  if (uniqueRoutes.length > 1) {
    return failure({
      reasonCode: "runtime_mapping_ambiguous",
      failedStep: "runtime_mapping_match",
      nextAction:
        "Narrow classHint/methodHint (or provide lineHint plus static discovery) and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `mappingsBaseUrl=${endpointUrl}`,
        `classHint=${input.classHint}`,
        `methodHint=${input.methodHint}`,
        `runtimeRouteMatches=${uniqueRoutes.length}`,
        `runtimeRouteCandidates=${uniqueRoutes.map((route) => `${route.httpMethod} ${route.path}`).join("|")}`,
      ],
    });
  }

  const selected = uniqueRoutes[0]!;
  if (!["GET", "POST", "PUT", "PATCH", "DELETE"].includes(selected.httpMethod)) {
    return failure({
      reasonCode: "runtime_mappings_invalid_payload",
      failedStep: "runtime_mapping_match",
      nextAction:
        "Return actuator mappings with standard HTTP methods (GET/POST/PUT/PATCH/DELETE) and rerun route_synthesis with action=create_recipe.",
      evidence: [
        `mappingsBaseUrl=${endpointUrl}`,
        `selectedMethod=${selected.httpMethod}`,
        `selectedPath=${selected.path}`,
      ],
    });
  }

  return {
    status: "ok",
    requestCandidate: {
      method: selected.httpMethod as "GET" | "POST" | "PUT" | "PATCH" | "DELETE",
      path: selected.path,
      queryTemplate: "",
      fullUrlHint: selected.path,
      rationale: [
        "Resolved HTTP mapping from Spring Actuator runtime mappings endpoint.",
        `Runtime handler: ${input.classHint}#${input.methodHint}`,
      ],
    },
    evidence: [
      `mapping_source=runtime_actuator`,
      `mappingsBaseUrl=${endpointUrl}`,
      `runtime_handler=${input.classHint}#${input.methodHint}`,
    ],
    attemptedStrategies: [RUNTIME_ATTEMPTED_STRATEGY],
  };
}
