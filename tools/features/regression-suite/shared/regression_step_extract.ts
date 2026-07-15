import type {
  PlanStep,
  PlanStepExtract,
  StepExtractValidationResult,
} from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";
import type {
  RegressionRunStepExtractApplyResult,
  RegressionRunStepExtractResult,
} from "@tools-regression-execution-plan-spec/models/regression_run_artifact.model";
import { readValueByPath } from "@tools-core/object_path_read";

function hasNonBlank(value: unknown): boolean {
  return typeof value !== "undefined" && value !== null && String(value).trim() !== "";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function validateStepExtracts(steps: PlanStep[]): StepExtractValidationResult {
  for (const step of steps) {
    if (typeof step.extract === "undefined") continue;
    if (!Array.isArray(step.extract)) {
      return {
        ok: false,
        reasonCode: "step_extract_invalid",
        requiredUserAction: [`Set steps[].extract to an array for step '${step.id}'.`],
      };
    }

    for (const raw of step.extract) {
      if (!isRecord(raw)) {
        return {
          ok: false,
          reasonCode: "step_extract_invalid",
          requiredUserAction: [`Ensure all extract entries for step '${step.id}' are objects.`],
        };
      }
      const extract = raw as PlanStepExtract;
      if (!hasNonBlank(extract.from)) {
        return {
          ok: false,
          reasonCode: "step_extract_invalid",
          requiredUserAction: [`Set non-empty extract[].from for step '${step.id}'.`],
        };
      }
      if (!hasNonBlank(extract.as)) {
        return {
          ok: false,
          reasonCode: "step_extract_invalid",
          requiredUserAction: [`Set non-empty extract[].as for step '${step.id}' (from='${extract.from}').`],
        };
      }
      if (typeof extract.required !== "undefined" && typeof extract.required !== "boolean") {
        return {
          ok: false,
          reasonCode: "step_extract_invalid",
          requiredUserAction: [
            `Set extract[].required to true or false for step '${step.id}' (from='${extract.from}').`,
          ],
        };
      }
      if (typeof extract.scope !== "undefined" && extract.scope !== "plan" && extract.scope !== "suite") {
        return {
          ok: false,
          reasonCode: "step_extract_invalid",
          requiredUserAction: [
            `Set extract[].scope to 'plan' or 'suite' for step '${step.id}' (from='${extract.from}').`,
          ],
        };
      }
      if (typeof extract.secret !== "undefined" && typeof extract.secret !== "boolean") {
        return {
          ok: false,
          reasonCode: "step_extract_invalid",
          requiredUserAction: [
            `Set extract[].secret to true or false for step '${step.id}' (from='${extract.from}').`,
          ],
        };
      }
    }
  }

  return { ok: true };
}

export function applyStepExtract(
  output: Record<string, unknown>,
  extract: PlanStepExtract[] | undefined,
  context: Record<string, unknown>,
): Record<string, unknown> {
  return applyStepExtractWithDiagnostics(output, extract, context).context;
}

export function applyStepExtractWithDiagnostics(
  output: Record<string, unknown>,
  extract: PlanStepExtract[] | undefined,
  context: Record<string, unknown>,
): RegressionRunStepExtractApplyResult {
  if (!extract?.length) {
    return {
      context,
      outcomes: [],
      hasRequiredUnresolved: false,
    };
  }

  const next = { ...context };
  const outcomes: RegressionRunStepExtractResult[] = [];
  let hasRequiredUnresolved = false;

  for (const mapping of extract) {
    const value = readValueByPath(output, mapping.from);
    const resolved = typeof value !== "undefined";
    const required = mapping.required === true;
    if (resolved) {
      next[mapping.as] = value;
    } else if (required) {
      hasRequiredUnresolved = true;
    }
    outcomes.push({
      from: mapping.from,
      as: mapping.as,
      required,
      status: resolved ? "resolved" : "unresolved",
      reasonCode: resolved ? "ok" : "extract_path_missing",
    });
  }

  return {
    context: next,
    outcomes,
    hasRequiredUnresolved,
  };
}
