import { promises as fs } from "node:fs";
import path from "node:path";

import {
  resolveSecurityRunRootAbs,
  type SecurityRunArtifact,
} from "@tools-security-execution-plan-spec";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function readSecurityRunArtifact(args: {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
  runId: string;
}): Promise<
  | { ok: true; artifact: SecurityRunArtifact; pathAbs: string }
  | { ok: false; reasonCode: "security_run_artifact_missing" | "security_run_artifact_invalid"; pathAbs: string }
> {
  const runDirAbs = await resolveSecurityRunRootAbs(args);
  const pathAbs = path.join(runDirAbs, "execution.result.json");
  let raw: string;
  try {
    raw = await fs.readFile(pathAbs, "utf8");
  } catch {
    return { ok: false, reasonCode: "security_run_artifact_missing", pathAbs };
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!isRecord(parsed) || parsed.suiteType !== "security" || parsed.schemaVersion !== "1.0.0") {
      return { ok: false, reasonCode: "security_run_artifact_invalid", pathAbs };
    }
    const artifact = parsed as unknown as SecurityRunArtifact;
    return { ok: true, artifact, pathAbs };
  } catch {
    return { ok: false, reasonCode: "security_run_artifact_invalid", pathAbs };
  }
}
