import { promises as fs } from "node:fs";
import path from "node:path";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "@/tools/core/artifact_management/actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";
import { readJsonFile, writeJsonFile } from "@/tools/core/artifact_management/shared/json_io.util";
import { resolveProjectName } from "@/tools/core/artifact_management/shared/project_resolution.util";

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0);
}

export async function handleRegressionPlanArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"regression_plan">,
): Promise<ArtifactActionResult> {
  const projectName = await resolveProjectName(ctx.workspaceRootAbs, request.input.projectName);
  const plansRoot = path.join(ctx.workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression");
  if (request.action === "list") {
    const plans = await fs.readdir(plansRoot, { withFileTypes: true }).catch(() => []);
    const planNames = plans
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((a, b) => a.localeCompare(b));
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planNames,
    });
  }

  const planName = request.input.planName?.trim();
  if (!planName) {
    return buildFailClosedArtifactResponse({
      reasonCode: "plan_name_required",
      reason: "planName is required for regression_plan action",
      reasonMeta: { action: request.action },
    });
  }
  const planRoot = path.join(plansRoot, planName);

  if (request.action === "read") {
    const selectors = asStringArray(request.input.query?.select);
    const includeAll = selectors.length === 0;
    if (includeAll) {
      const metadata = (await readJsonFile(path.join(planRoot, "metadata.json"))) as Record<string, unknown>;
      const contract = (await readJsonFile(path.join(planRoot, "contract.json"))) as Record<string, unknown>;
      const steps = Array.isArray(contract.steps) ? contract.steps : [];
      const targets = Array.isArray(contract.targets) ? contract.targets : [];
      const prerequisites = Array.isArray(contract.prerequisites) ? contract.prerequisites : [];
      return okArtifactResponse({
        resultType: "artifact",
        status: "ok",
        artifactType: request.artifactType,
        action: request.action,
        projectName,
        planName,
        summary: {
          intent: metadata.execution && typeof metadata.execution === "object" ? (metadata.execution as Record<string, unknown>).intent ?? null : null,
          stepCount: steps.length,
          targetCount: targets.length,
          prerequisiteCount: prerequisites.length,
        },
      });
    }
    const artifact: Record<string, unknown> = {};
    if (selectors.includes("metadata")) {
      artifact.metadata = await readJsonFile(path.join(planRoot, "metadata.json"));
    }
    if (selectors.includes("contract")) {
      artifact.contract = await readJsonFile(path.join(planRoot, "contract.json"));
    }
    if (selectors.includes("plan")) {
      artifact.plan = await fs.readFile(path.join(planRoot, "plan.md"), "utf8").catch(() => "");
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
      artifact,
    });
  }

  if (request.action === "validate") {
    await readJsonFile(path.join(planRoot, "metadata.json"));
    await readJsonFile(path.join(planRoot, "contract.json"));
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
    });
  }

  if (!request.input.payload) {
    return buildFailClosedArtifactResponse({
      reasonCode: "artifact_payload_required",
      reason: "payload is required for upsert",
      reasonMeta: { artifactType: request.artifactType, action: request.action, projectName, planName },
    });
  }
  await writeJsonFile(path.join(planRoot, "metadata.json"), request.input.payload.metadata ?? {});
  await writeJsonFile(path.join(planRoot, "contract.json"), request.input.payload.contract ?? {});
  if (typeof request.input.payload.plan === "string") {
    await fs.writeFile(path.join(planRoot, "plan.md"), request.input.payload.plan, "utf8");
  }
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    projectName,
    planName,
    path: planRoot,
  });
}
