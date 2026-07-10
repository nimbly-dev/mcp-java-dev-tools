import type { PerformancePlanMetadata } from "../models/performance_suite.model";
export type { PerformancePlanMetadata } from "../models/performance_suite.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asTrimmedString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

export function parsePerformancePlanMetadata(
  input: unknown,
): { ok: true; metadata: PerformancePlanMetadata } | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  if (!isRecord(input)) {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set metadata.json as an object for the performance plan."],
    };
  }
  const suiteType = asTrimmedString(input.suiteType);
  const execution = isRecord(input.execution) ? input.execution : null;
  const intent = execution ? asTrimmedString(execution.intent) : undefined;
  if (suiteType !== "performance" || intent !== "performance") {
    return {
      ok: false,
      reasonCode: "performance_plan_invalid",
      requiredUserAction: ["Set metadata.suiteType=performance and metadata.execution.intent=performance."],
    };
  }
  return {
    ok: true,
    metadata: {
      ...(typeof input.specVersion === "string" ? { specVersion: input.specVersion } : {}),
      suiteType: "performance",
      execution: { intent: "performance" },
    },
  };
}
