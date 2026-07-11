import { isDeepStrictEqual } from "node:util";
import { readValueByPath } from "@tools-core/object_path_read";
import type {
  PlanStepCondition,
  PlanStepConditionPredicate,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type { ConditionReasonCode } from "../models/regression_suite.model";
export type { CorrelationKeyResolution } from "../models/regression_suite.model";

export function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

export function asPositiveInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;
}

export function tryParseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

export function correlationJsonBodyCandidatePaths(sourcePath: string): string[] {
  const candidates = new Set<string>([sourcePath]);
  if (sourcePath.startsWith("response.body.")) {
    candidates.add(sourcePath.slice("response.body.".length));
  }
  if (sourcePath.startsWith("response.bodyJson.")) {
    candidates.add(sourcePath.slice("response.bodyJson.".length));
  }
  if (sourcePath === "response.body" || sourcePath === "response.bodyJson") {
    candidates.add("");
  }
  return Array.from(candidates).filter((value) => value.length > 0);
}

export function resolveConditionLeftValue(args: {
  left: string;
  context: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  currentOrder: number;
}):
  | { ok: true; actual: unknown }
  | {
      ok: false;
      reasonCode: ConditionReasonCode;
    } {
  if (args.left.startsWith("context.")) {
    return {
      ok: true,
      actual: readValueByPath(args.context, args.left.slice("context.".length)),
    };
  }
  const stepMatch = args.left.match(/^step\[(\d+)\]\.(.+)$/);
  if (!stepMatch) {
    return { ok: false, reasonCode: "step_condition_path_missing" };
  }
  const stepOrder = Number(stepMatch[1]);
  const pathAfter = stepMatch[2];
  if (typeof pathAfter !== "string" || pathAfter.length === 0) {
    return { ok: false, reasonCode: "step_condition_path_missing" };
  }
  if (!Number.isFinite(stepOrder) || stepOrder < 1) {
    return { ok: false, reasonCode: "step_condition_type_mismatch" };
  }
  if (stepOrder >= args.currentOrder) {
    return { ok: false, reasonCode: "step_condition_forward_reference" };
  }
  const stepOutput = args.stepOutputsByOrder[stepOrder];
  if (!stepOutput) {
    return { ok: false, reasonCode: "step_condition_path_missing" };
  }
  return {
    ok: true,
    actual: readValueByPath(stepOutput, pathAfter),
  };
}

export function evaluatePredicate(args: {
  condition: PlanStepConditionPredicate;
  context: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  currentOrder: number;
}): { status: true | false | "blocked_invalid"; reasonCode?: ConditionReasonCode } {
  const left = resolveConditionLeftValue({
    left: args.condition.left,
    context: args.context,
    stepOutputsByOrder: args.stepOutputsByOrder,
    currentOrder: args.currentOrder,
  });
  if (!left.ok) {
    return { status: "blocked_invalid", reasonCode: left.reasonCode };
  }
  if (args.condition.op === "exists") {
    return { status: typeof left.actual !== "undefined" };
  }
  if (args.condition.op === "equals") {
    return { status: isDeepStrictEqual(left.actual, args.condition.right) };
  }
  if (args.condition.op === "not_equals") {
    return { status: !isDeepStrictEqual(left.actual, args.condition.right) };
  }
  if (args.condition.op === "in") {
    if (!Array.isArray(args.condition.right)) {
      return { status: "blocked_invalid", reasonCode: "step_condition_type_mismatch" };
    }
    return {
      status: args.condition.right.some((item) => isDeepStrictEqual(item, left.actual)),
    };
  }
  return { status: "blocked_invalid", reasonCode: "step_condition_operator_invalid" };
}

export function evaluateStepCondition(args: {
  when: PlanStepCondition;
  context: Record<string, unknown>;
  stepOutputsByOrder: Record<number, Record<string, unknown>>;
  currentOrder: number;
}): { status: true | false | "blocked_invalid"; reasonCode?: ConditionReasonCode } {
  const node = args.when as unknown as Record<string, unknown>;
  if ("all" in node) {
    if (!Array.isArray(node.all) || node.all.length === 0) {
      return { status: "blocked_invalid", reasonCode: "step_condition_malformed" };
    }
    for (const child of node.all as PlanStepCondition[]) {
      const evalChild = evaluateStepCondition({
        when: child,
        context: args.context,
        stepOutputsByOrder: args.stepOutputsByOrder,
        currentOrder: args.currentOrder,
      });
      if (evalChild.status === "blocked_invalid") return evalChild;
      if (evalChild.status === false) return { status: false };
    }
    return { status: true };
  }
  if ("any" in node) {
    if (!Array.isArray(node.any) || node.any.length === 0) {
      return { status: "blocked_invalid", reasonCode: "step_condition_malformed" };
    }
    let hasTrue = false;
    for (const child of node.any as PlanStepCondition[]) {
      const evalChild = evaluateStepCondition({
        when: child,
        context: args.context,
        stepOutputsByOrder: args.stepOutputsByOrder,
        currentOrder: args.currentOrder,
      });
      if (evalChild.status === "blocked_invalid") return evalChild;
      if (evalChild.status === true) hasTrue = true;
    }
    return { status: hasTrue };
  }
  if ("not" in node) {
    const notCondition = node.not as PlanStepCondition;
    const evalNot = evaluateStepCondition({
      when: notCondition,
      context: args.context,
      stepOutputsByOrder: args.stepOutputsByOrder,
      currentOrder: args.currentOrder,
    });
    if (evalNot.status === "blocked_invalid") return evalNot;
    return { status: !evalNot.status };
  }
  return evaluatePredicate({
    condition: node as unknown as PlanStepConditionPredicate,
    context: args.context,
    stepOutputsByOrder: args.stepOutputsByOrder,
    currentOrder: args.currentOrder,
  });
}

export function resolveBlockedShape(preflight: {
  status: string;
  reasonCode: string;
  missing: string[];
  checks?: string[];
  nextAction?: string;
  requiredUserAction: string[];
}) {
  return {
    status: preflight.status,
    reasonCode: preflight.reasonCode,
    missing: preflight.missing,
    checks: preflight.checks ?? [],
    ...(typeof preflight.nextAction === "string" ? { nextAction: preflight.nextAction } : {}),
    requiredUserAction: preflight.requiredUserAction,
  };
}
