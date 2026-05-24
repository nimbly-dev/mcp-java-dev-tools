import { promises as fs } from "node:fs";
import path from "node:path";

import type { PlanContract } from "@tools-regression-execution-plan-spec/models/regression_execution_plan_spec.model";

export async function loadPlanContract(input: {
  plansRootAbs: string;
  planName: string;
}): Promise<PlanContract | null> {
  const contractPathAbs = path.join(input.plansRootAbs, input.planName, "contract.json");
  try {
    const text = await fs.readFile(contractPathAbs, "utf8");
    const parsed = JSON.parse(text) as PlanContract;
    if (!Array.isArray(parsed.steps)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}
