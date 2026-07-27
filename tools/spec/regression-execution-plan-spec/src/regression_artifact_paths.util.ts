import { resolvePlansRootAbs } from "@tools-execution-plan-spec/artifact_paths.util";

export { resolvePlansRootAbs } from "@tools-execution-plan-spec/artifact_paths.util";

export async function resolveRegressionPlansRootAbs(
  workspaceRootAbs: string,
  projectName?: string,
): Promise<string> {
  return resolvePlansRootAbs({
    workspaceRootAbs,
    suiteType: "regression",
    ...(typeof projectName === "string" ? { projectName } : {}),
  });
}
