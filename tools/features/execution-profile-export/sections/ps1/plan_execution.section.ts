import type { ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { renderPs1PlanExecutionSection } from "@tools-export-execution-profile/renderers/plan.command.ps1.renderer";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { resolvePlanBaseUrls } from "@tools-export-execution-profile/sections/sh/plan_execution.section";

export async function buildPs1PlanExecutionSection(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<string[]> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
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
