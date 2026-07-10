import type { ExecutionProfileExportPlanRun } from "../../models/execution_profile_export.model";
import { renderPs1PlanExecutionSection } from "../../renderers/plan.command.ps1.renderer";
import { resolveRegressionPlansRootAbs } from "../../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";
import { resolvePlanBaseUrls } from "../sh/plan_execution.section";

export async function buildPs1PlanExecutionSection(input: {
  workspaceRootAbs: string;
  projectName?: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<string[]> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs, input.projectName);
  const planBaseUrls = await resolvePlanBaseUrls({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace: input.workspace,
    executionProfile: input.executionProfile,
    planRuns: input.planRuns,
  });
  return await renderPs1PlanExecutionSection({
    planRuns: input.planRuns,
    plansRootAbs,
    planBaseUrls,
  });
}
