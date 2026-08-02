import fs from "node:fs";
import path from "node:path";

import { loadProbeRegistry } from "@tools-core/probe-registry";
import type {
  SecurityInstrumentationTarget,
  SecurityRuntimeTarget,
} from "@tools-security-execution-plan-spec";

export type SidecarInstrumentationSelection = {
  runtimeTargets: SecurityRuntimeTarget[];
  instrumentationTargets: SecurityInstrumentationTarget[];
};

export type SidecarInstrumentationSelectionResult =
  | { ok: true; selection: SidecarInstrumentationSelection }
  | { ok: false; reasonCode: string; reason: string };

function isLikelyExactClassName(value: string): boolean {
  const lastSegment = value.slice(value.lastIndexOf(".") + 1);
  return lastSegment.length > 0 && [...lastSegment].some((character) => /[A-Z$]/.test(character));
}

function patternToRegex(pattern: string): RegExp {
  const trimmed = pattern.trim();
  const hasWildcard = trimmed.includes("*");
  if (!hasWildcard && isLikelyExactClassName(trimmed)) {
    return new RegExp(`^${escapeRegExp(trimmed)}(\\$.*)?$`);
  }
  let glob = trimmed;
  if (!hasWildcard) glob = trimmed.endsWith(".") ? `${trimmed}**` : `${trimmed}.**`;
  let regex = "^";
  for (let index = 0; index < glob.length; index += 1) {
    const character = glob[index] ?? "";
    if (character === "*") {
      const double = glob[index + 1] === "*";
      regex += double ? ".*" : "[^.]*";
      if (double) index += 1;
    } else {
      regex += escapeRegExp(character);
    }
  }
  return new RegExp(`${regex}$`);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function matchesAny(patterns: string[], fqcn: string): boolean {
  return patterns.some((pattern) => {
    try {
      return patternToRegex(pattern).test(fqcn);
    } catch {
      return false;
    }
  });
}

function targetClassFqcn(strictLineKey: string): string {
  return strictLineKey.slice(0, strictLineKey.indexOf("#"));
}

export function validateSidecarInstrumentationSelection(args: {
  workspaceRootAbs: string;
  runtimeTargets: SecurityRuntimeTarget[];
  instrumentationTargets?: SecurityInstrumentationTarget[];
}): SidecarInstrumentationSelectionResult {
  const instrumentationTargets = args.instrumentationTargets ?? [];
  if (instrumentationTargets.length === 0) {
    return {
      ok: true,
      selection: { runtimeTargets: args.runtimeTargets, instrumentationTargets: [] },
    };
  }

  const registryPath = path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json");
  if (!fs.existsSync(registryPath)) {
    return {
      ok: false,
      reasonCode: "security_sidecar_probe_config_missing",
      reason: "Sidecar instrumentation targets require the workspace probe-config.json Artifact.",
    };
  }

  let registry: ReturnType<typeof loadProbeRegistry>;
  try {
    registry = loadProbeRegistry({
      filePath: registryPath,
      workspaceRootAbs: args.workspaceRootAbs,
    });
  } catch (error) {
    return {
      ok: false,
      reasonCode: "security_sidecar_probe_config_invalid",
      reason: error instanceof Error ? error.message : String(error),
    };
  }

  const targetsById = new Map(instrumentationTargets.map((target) => [target.id, target]));
  for (const runtimeTarget of args.runtimeTargets) {
    if (!runtimeTarget.instrumentationTargetRef) continue;
    const instrumentationTarget = targetsById.get(runtimeTarget.instrumentationTargetRef);
    if (!instrumentationTarget) {
      return {
        ok: false,
        reasonCode: "security_sidecar_instrumentation_target_missing",
        reason: `Runtime target '${runtimeTarget.id}' references an unavailable instrumentation target.`,
      };
    }
    const probe = registry.probesById.get(runtimeTarget.probeId);
    if (!probe) {
      return {
        ok: false,
        reasonCode: "security_sidecar_probe_id_unconfigured",
        reason: `Probe '${runtimeTarget.probeId}' is not configured in the workspace probe registry.`,
      };
    }
    const expectedClassFqcn = targetClassFqcn(runtimeTarget.strictLineKey);
    if (expectedClassFqcn !== instrumentationTarget.classFqcn) {
      return {
        ok: false,
        reasonCode: "security_sidecar_instrumentation_target_mismatch",
        reason: `Instrumentation target '${instrumentationTarget.id}' does not match runtime target '${runtimeTarget.id}'.`,
      };
    }
    if (!matchesAny(probe.include, instrumentationTarget.classFqcn)) {
      return {
        ok: false,
        reasonCode: "security_sidecar_instrumentation_not_configured",
        reason: `Probe '${runtimeTarget.probeId}' does not include selected ${instrumentationTarget.scope} class '${instrumentationTarget.classFqcn}'.`,
      };
    }
    if (matchesAny(probe.exclude, instrumentationTarget.classFqcn)) {
      return {
        ok: false,
        reasonCode: "security_sidecar_instrumentation_excluded",
        reason: `Probe '${runtimeTarget.probeId}' excludes selected ${instrumentationTarget.scope} class '${instrumentationTarget.classFqcn}'.`,
      };
    }
  }

  return {
    ok: true,
    selection: { runtimeTargets: args.runtimeTargets, instrumentationTargets },
  };
}
