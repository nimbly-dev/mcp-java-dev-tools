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

function readRequiredWindow(value: unknown): { offset: number; limit: number } {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("window_query_required");
  }
  const raw = value as Record<string, unknown>;
  const offset = raw.offset;
  const limit = raw.limit;
  if (typeof offset !== "number" || !Number.isInteger(offset) || offset < 0) {
    throw new Error("window_query_required");
  }
  if (typeof limit !== "number" || !Number.isInteger(limit) || limit <= 0) {
    throw new Error("window_query_required");
  }
  return { offset, limit };
}

function toWindowedSection<T>(items: T[], window: { offset: number; limit: number }) {
  const start = Math.min(window.offset, items.length);
  const end = Math.min(start + window.limit, items.length);
  const page = items.slice(start, end);
  return {
    offset: start,
    limit: window.limit,
    returned: page.length,
    total: items.length,
    items: page,
  };
}

function collectUnsupportedStepProtocols(contract: Record<string, unknown>): Array<{
  stepId: string;
  protocol: string;
}> {
  const allowedProtocols = new Set(["http"]);
  const steps = Array.isArray(contract.steps) ? contract.steps : [];
  const out: Array<{ stepId: string; protocol: string }> = [];
  for (const rawStep of steps) {
    if (typeof rawStep !== "object" || rawStep === null || Array.isArray(rawStep)) continue;
    const step = rawStep as Record<string, unknown>;
    const protocol = typeof step.protocol === "string" ? step.protocol.trim() : "";
    if (!protocol) continue;
    if (allowedProtocols.has(protocol)) continue;
    const stepId = typeof step.id === "string" && step.id.trim().length > 0 ? step.id : "unknown_step";
    out.push({ stepId, protocol });
  }
  return out;
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
    const needsMetadata = includeAll || selectors.includes("metadata") || selectors.includes("summary");
    const needsContract =
      selectors.includes("contract") ||
      selectors.includes("summary") ||
      selectors.includes("targets") ||
      selectors.includes("prerequisites") ||
      selectors.includes("steps");

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
    const metadata = needsMetadata ? ((await readJsonFile(path.join(planRoot, "metadata.json"))) as Record<string, unknown>) : undefined;
    const contract = needsContract ? ((await readJsonFile(path.join(planRoot, "contract.json"))) as Record<string, unknown>) : undefined;
    const artifact: Record<string, unknown> = {};
    const response: Record<string, unknown> = {
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
    };

    if (selectors.includes("summary")) {
      const steps = contract && Array.isArray(contract.steps) ? contract.steps : [];
      const targets = contract && Array.isArray(contract.targets) ? contract.targets : [];
      const prerequisites = contract && Array.isArray(contract.prerequisites) ? contract.prerequisites : [];
      response.summary = {
        intent:
          metadata && metadata.execution && typeof metadata.execution === "object"
            ? (metadata.execution as Record<string, unknown>).intent ?? null
            : null,
        stepCount: steps.length,
        targetCount: targets.length,
        prerequisiteCount: prerequisites.length,
      };
    }
    if (selectors.includes("targets")) {
      response.targets = contract && Array.isArray(contract.targets) ? contract.targets : [];
    }
    if (selectors.includes("prerequisites")) {
      const prerequisites = contract && Array.isArray(contract.prerequisites) ? contract.prerequisites : [];
      const window = readRequiredWindow(request.input.query?.prerequisites);
      response.prerequisites = toWindowedSection(prerequisites, window);
    }
    if (selectors.includes("steps")) {
      const steps = contract && Array.isArray(contract.steps) ? contract.steps : [];
      const window = readRequiredWindow(request.input.query?.steps);
      response.steps = toWindowedSection(steps, window);
    }
    if (selectors.includes("metadata")) {
      artifact.metadata = metadata ?? (await readJsonFile(path.join(planRoot, "metadata.json")));
    }
    if (selectors.includes("contract")) {
      artifact.contract = contract ?? (await readJsonFile(path.join(planRoot, "contract.json")));
    }
    if (selectors.includes("plan")) {
      artifact.plan = await fs.readFile(path.join(planRoot, "plan.md"), "utf8").catch(() => "");
    }
    if (Object.keys(artifact).length > 0) {
      response.artifact = artifact;
    }
    return okArtifactResponse(response);
  }

  if (request.action === "validate") {
    await readJsonFile(path.join(planRoot, "metadata.json"));
    const contract = (await readJsonFile(path.join(planRoot, "contract.json"))) as Record<string, unknown>;
    const unsupported = collectUnsupportedStepProtocols(contract);
    if (unsupported.length > 0) {
      return buildFailClosedArtifactResponse({
        reasonCode: "transport_protocol_mismatch",
        reason: "regression plan contains step protocol not supported by execution_orchestration",
        reasonMeta: {
          artifactType: request.artifactType,
          action: request.action,
          projectName,
          planName,
          allowedProtocols: ["http"],
          unsupportedSteps: unsupported,
        },
      });
    }
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
