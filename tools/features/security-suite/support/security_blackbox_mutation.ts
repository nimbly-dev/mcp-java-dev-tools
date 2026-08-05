import type {
  SecurityAttackRequest,
  SecurityHttpBaselineRequest,
} from "@tools-security-execution-plan-spec";

import type {
  SecurityBlackboxKnowledgeRule,
  SecurityMutationSelector,
} from "./security_blackbox_knowledge";

type SecurityMutation = SecurityBlackboxKnowledgeRule["caseTemplates"][number]["attack"];

function cloneValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(cloneValue);
  if (value && typeof value === "object") {
    const output: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value)) output[key] = cloneValue(child);
    return output;
  }
  return value;
}

function cloneRecord(
  value: Record<string, string> | undefined,
): Record<string, string> | undefined {
  return value ? { ...value } : undefined;
}

function selectorTargetName(
  selector: Extract<SecurityMutationSelector, { kind: "path_parameter" | "query_parameter" }>,
  values: Record<string, string>,
): string {
  if (selector.name !== "*") {
    if (!Object.prototype.hasOwnProperty.call(values, selector.name)) {
      throw new Error(`security_blackbox_mutation_target_missing:${selector.kind}`);
    }
    return selector.name;
  }
  const first = Object.keys(values).sort()[0];
  if (!first) throw new Error(`security_blackbox_mutation_target_missing:${selector.kind}`);
  return first;
}

function headerTargetName(selector: Extract<SecurityMutationSelector, { kind: "header" }>): string {
  if (selector.name.trim().length === 0) {
    throw new Error("security_blackbox_mutation_target_missing:header");
  }
  return selector.name;
}

function decodeJsonPointer(pointer: string): string[] {
  if (pointer === "") return [];
  if (!pointer.startsWith("/")) {
    throw new Error("security_blackbox_mutation_selector_invalid:body");
  }
  return pointer
    .slice(1)
    .split("/")
    .map((segment) => segment.replaceAll("~1", "/").replaceAll("~0", "~"));
}

function assertSafeJsonPointerSegment(segment: string): void {
  if (segment === "__proto__" || segment === "prototype" || segment === "constructor") {
    throw new Error("security_blackbox_mutation_selector_invalid:body");
  }
}

function setBodyValue(body: unknown, pointer: string, value: unknown): unknown {
  const segments = decodeJsonPointer(pointer);
  segments.forEach(assertSafeJsonPointerSegment);
  if (segments.length === 0) return value;
  if (!body || typeof body !== "object") {
    throw new Error("security_blackbox_mutation_target_missing:body");
  }
  const root = cloneValue(body) as Record<string, unknown> | unknown[];
  let current: Record<string, unknown> | unknown[] = root;
  for (let index = 0; index < segments.length - 1; index += 1) {
    const segment = segments[index]!;
    const next = Array.isArray(current) ? current[Number(segment)] : current[segment];
    if (!next || typeof next !== "object") {
      throw new Error("security_blackbox_mutation_target_missing:body");
    }
    current = next as Record<string, unknown> | unknown[];
  }
  const finalSegment = segments.at(-1)!;
  if (Array.isArray(current)) {
    if (!/^\d+$/.test(finalSegment) || Number(finalSegment) >= current.length) {
      throw new Error("security_blackbox_mutation_target_missing:body");
    }
    current[Number(finalSegment)] = value;
  } else {
    if (!Object.prototype.hasOwnProperty.call(current, finalSegment)) {
      throw new Error("security_blackbox_mutation_target_missing:body");
    }
    current[finalSegment] = value;
  }
  return root;
}

function removeHeader(headers: Record<string, string>, name: string): void {
  const normalized = name.toLowerCase();
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === normalized) delete headers[key];
  }
}

function setHeader(headers: Record<string, string>, name: string, value: string): void {
  removeHeader(headers, name);
  headers[name] = value;
}

export function mutationRequiresAnonymousAuthentication(
  mutation: SecurityMutation["mutation"],
): boolean {
  return mutation === "missing-authentication" || mutation === "anonymous-boundary";
}

export function buildCatalogAttackRequest(args: {
  baseline: SecurityHttpBaselineRequest;
  mutation: SecurityMutation;
  payloadTemplate: string;
}): SecurityAttackRequest {
  const pathParameters = cloneRecord(args.baseline.pathParameters);
  const query = cloneRecord(args.baseline.query);
  const headers = cloneRecord(args.baseline.headers);
  const attack: SecurityAttackRequest = {
    ...(pathParameters ? { pathParameters } : {}),
    ...(query ? { query } : {}),
    ...(headers ? { headers } : {}),
    ...(args.baseline.body !== undefined ? { body: cloneValue(args.baseline.body) } : {}),
    expect: {
      outcome: args.mutation.expectedOutcome,
      statusCodes: args.mutation.statusCodes,
    },
  };
  const remove = mutationRequiresAnonymousAuthentication(args.mutation.mutation);
  const selector = args.mutation.selector;
  if (selector.kind === "path_parameter") {
    const pathParameters = attack.pathParameters ?? {};
    const name = selectorTargetName(selector, pathParameters);
    if (remove) delete pathParameters[name];
    else pathParameters[name] = args.payloadTemplate;
    attack.pathParameters = pathParameters;
  } else if (selector.kind === "query_parameter") {
    const query = attack.query ?? {};
    const name = selectorTargetName(selector, query);
    if (remove) delete query[name];
    else query[name] = args.payloadTemplate;
    attack.query = query;
  } else if (selector.kind === "header") {
    const headers = attack.headers ?? {};
    const name = headerTargetName(selector);
    if (remove) removeHeader(headers, name);
    else setHeader(headers, name, args.payloadTemplate);
    attack.headers = headers;
  } else {
    attack.body = setBodyValue(attack.body, selector.jsonPointer, args.payloadTemplate);
  }
  return attack;
}
