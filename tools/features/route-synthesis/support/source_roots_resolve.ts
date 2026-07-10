import * as fs from "node:fs/promises";
import * as path from "node:path";

export const MAX_ADDITIONAL_SOURCE_ROOTS = 10;

export type AdditionalSourceRootsResolution =
  | {
      ok: true;
      normalizedAdditionalSourceRoots: string[];
    }
  | {
      ok: false;
      reasonCode: "additional_source_roots_invalid" | "additional_source_roots_limit_exceeded";
      failedStep: "input_validation";
      reason: string;
      nextAction: string;
      evidence: string[];
    };

function unique(values: string[]): string[] {
  return values.filter((value, index, arr) => arr.indexOf(value) === index);
}

export async function resolveAdditionalSourceRoots(args: {
  workspaceRootAbs: string;
  additionalSourceRoots?: string[];
}): Promise<AdditionalSourceRootsResolution> {
  const requested = (args.additionalSourceRoots ?? []).map((value) => value.trim());
  const nonEmpty = requested.filter((value) => value.length > 0);

  if (nonEmpty.length > MAX_ADDITIONAL_SOURCE_ROOTS) {
    return {
      ok: false,
      reasonCode: "additional_source_roots_limit_exceeded",
      failedStep: "input_validation",
      reason: `additionalSourceRoots exceeds max count (${MAX_ADDITIONAL_SOURCE_ROOTS})`,
      nextAction: `Provide at most ${MAX_ADDITIONAL_SOURCE_ROOTS} additionalSourceRoots entries and rerun.`,
      evidence: [`providedCount=${nonEmpty.length}`],
    };
  }

  const normalizedAdditionalSourceRoots: string[] = [];
  for (const raw of nonEmpty) {
    const resolved = path.isAbsolute(raw)
      ? path.resolve(raw)
      : path.resolve(args.workspaceRootAbs, raw);
    let stat;
    try {
      stat = await fs.stat(resolved);
    } catch {
      return {
        ok: false,
        reasonCode: "additional_source_roots_invalid",
        failedStep: "input_validation",
        reason: "additionalSourceRoots contains a path that does not exist",
        nextAction:
          "Provide existing directory paths in additionalSourceRoots (absolute or relative to workspace) and rerun.",
        evidence: [`invalidRoot=${resolved}`],
      };
    }
    if (!stat.isDirectory()) {
      return {
        ok: false,
        reasonCode: "additional_source_roots_invalid",
        failedStep: "input_validation",
        reason: "additionalSourceRoots contains a path that is not a directory",
        nextAction:
          "Provide directory paths in additionalSourceRoots (absolute or relative to workspace) and rerun.",
        evidence: [`invalidRoot=${resolved}`],
      };
    }
    normalizedAdditionalSourceRoots.push(resolved);
  }

  return {
    ok: true,
    normalizedAdditionalSourceRoots: unique(normalizedAdditionalSourceRoots),
  };
}
