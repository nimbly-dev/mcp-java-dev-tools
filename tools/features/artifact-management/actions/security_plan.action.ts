import { promises as fs } from "node:fs";
import path from "node:path";

import {
  validateSecurityArtifactSegment,
  validateSecurityPlanContract,
} from "@tools-security-execution-plan-spec";
import { validateSidecarInstrumentationSelection } from "@tools-feature-security-suite";
import type { SecurityPlanContract } from "@tools-security-execution-plan-spec";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "./types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import { readJsonFile, writeJsonFile } from "../shared/json_io";
import { resolveProjectName } from "../shared/project_resolution";

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is string => typeof entry === "string" && entry.trim().length > 0,
  );
}

function validateSidecarSelection(
  workspaceRootAbs: string,
  contract: SecurityPlanContract,
): { ok: true } | { ok: false; reasonCode: string; reason: string } {
  if (contract.securityMode !== "sidecar_assisted") return { ok: true };
  const selection = validateSidecarInstrumentationSelection({
    workspaceRootAbs,
    runtimeTargets: contract.runtimeTargets,
    ...(contract.instrumentationTargets
      ? { instrumentationTargets: contract.instrumentationTargets }
      : {}),
  });
  return selection.ok ? { ok: true } : selection;
}

export async function handleSecurityPlanArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"security_plan">,
): Promise<ArtifactActionResult> {
  const rawPlanName = request.input.planName?.trim();
  const rawProjectName = request.input.projectName?.trim();
  let projectNameInput: string | undefined;
  if (rawProjectName) {
    try {
      projectNameInput = validateSecurityArtifactSegment(rawProjectName, "project_name");
    } catch {
      return buildFailClosedArtifactResponse({
        reasonCode: "security_project_name_invalid",
        reason: "projectName must be a single safe Artifact path segment",
        reasonMeta: { action: request.action },
      });
    }
  }
  let planName: string | undefined;
  if (request.action !== "list") {
    if (!rawPlanName) {
      return buildFailClosedArtifactResponse({
        reasonCode: "plan_name_required",
        reason: "planName is required for security_plan action",
        reasonMeta: { action: request.action },
      });
    }
    try {
      planName = validateSecurityArtifactSegment(rawPlanName, "plan_name");
    } catch {
      return buildFailClosedArtifactResponse({
        reasonCode: "security_plan_name_invalid",
        reason: "planName must be a single safe Artifact path segment",
        reasonMeta: { action: request.action },
      });
    }
  }
  const projectName = await resolveProjectName(ctx.workspaceRootAbs, projectNameInput);
  const plansRoot = path.join(ctx.workspaceRootAbs, ".mcpjvm", projectName, "plans", "security");
  if (request.action === "list") {
    const plans = await fs.readdir(plansRoot, { withFileTypes: true }).catch(() => []);
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planNames: plans
        .filter((entry) => entry.isDirectory())
        .map((entry) => entry.name)
        .sort((a, b) => a.localeCompare(b)),
    });
  }

  if (!planName) {
    return buildFailClosedArtifactResponse({
      reasonCode: "plan_name_required",
      reason: "planName is required for security_plan action",
      reasonMeta: { action: request.action },
    });
  }
  const planRoot = path.join(plansRoot, planName);
  const metadataPath = path.join(planRoot, "metadata.json");
  const contractPath = path.join(planRoot, "contract.json");

  if (request.action === "read") {
    const selectors = asStringArray(request.input.query?.select);
    const includeAll = selectors.length === 0;
    const metadata =
      includeAll || selectors.includes("metadata") || selectors.includes("summary")
        ? ((await readJsonFile(metadataPath)) as Record<string, unknown>)
        : undefined;
    const contract =
      includeAll || selectors.includes("contract") || selectors.includes("summary")
        ? ((await readJsonFile(contractPath)) as Record<string, unknown>)
        : undefined;
    const output: Record<string, unknown> = {
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
    };
    if (includeAll || selectors.includes("summary")) {
      output.summary = {
        suiteType: contract?.suiteType ?? null,
        securityMode: contract?.securityMode ?? null,
        entrypointCount: Array.isArray(contract?.entrypoints) ? contract.entrypoints.length : 0,
        authenticationProfileCount: Array.isArray(contract?.authenticationProfiles)
          ? contract.authenticationProfiles.length
          : 0,
        attackProfileCount: Array.isArray(contract?.attackProfiles)
          ? contract.attackProfiles.length
          : 0,
      };
    }
    if (selectors.includes("metadata") || includeAll) output.artifact = { metadata };
    if (selectors.includes("contract") || includeAll) {
      output.artifact = { ...(output.artifact as Record<string, unknown> | undefined), contract };
    }
    if (selectors.includes("plan") || includeAll) {
      output.artifact = {
        ...(output.artifact as Record<string, unknown> | undefined),
        plan: await fs.readFile(path.join(planRoot, "plan.md"), "utf8").catch(() => ""),
      };
    }
    return okArtifactResponse(output);
  }

  if (request.action === "validate") {
    const contract = await readJsonFile(contractPath);
    const validated = validateSecurityPlanContract(contract);
    if (!validated.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: validated.reasonCode,
        reason: "security plan contract validation failed",
        reasonMeta: { projectName, planName, errors: validated.errors },
      });
    }
    const sidecarSelection = validateSidecarSelection(ctx.workspaceRootAbs, validated.contract);
    if (!sidecarSelection.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: sidecarSelection.reasonCode,
        reason: sidecarSelection.reason,
        reasonMeta: { projectName, planName },
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
      reasonMeta: {
        artifactType: request.artifactType,
        action: request.action,
        projectName,
        planName,
      },
    });
  }
  const contractPayload = request.input.payload.contract ?? {};
  const validated = validateSecurityPlanContract(contractPayload);
  if (!validated.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: validated.reasonCode,
      reason: "security plan contract validation failed",
      reasonMeta: { projectName, planName, errors: validated.errors },
    });
  }
  const sidecarSelection = validateSidecarSelection(ctx.workspaceRootAbs, validated.contract);
  if (!sidecarSelection.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: sidecarSelection.reasonCode,
      reason: sidecarSelection.reason,
      reasonMeta: { projectName, planName },
    });
  }
  await writeJsonFile(metadataPath, request.input.payload.metadata ?? {});
  await writeJsonFile(contractPath, contractPayload);
  if (typeof request.input.payload.plan === "string") {
    await fs.mkdir(planRoot, { recursive: true });
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
