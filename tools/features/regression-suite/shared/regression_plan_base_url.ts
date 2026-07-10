import { promises as fs } from "node:fs";
import path from "node:path";

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

export function inferComposeServiceNameCandidates(planName: string): string[] {
  const specBase = planName
    .replace(/-regression-spec$/i, "")
    .replace(/-smoke-spec$/i, "");
  const withoutService = specBase.replace(/-service$/i, "");
  return [...new Set([specBase, withoutService])].filter((candidate) => candidate.length > 0);
}

type ProbeRegistryFile = {
  defaultProfile?: string;
  workspaces?: Array<{ root?: string; profile?: string }>;
  profiles?: Record<
    string,
    {
      probes?: Record<
        string,
        {
          runtime?: {
            port?: unknown;
          };
        }
      >;
    }
  >;
};

function stripBom(input: string): string {
  return input.charCodeAt(0) === 0xfeff ? input.slice(1) : input;
}

function resolveProbeProfile(input: {
  registry: ProbeRegistryFile;
  workspaceRootAbs: string;
}): string | undefined {
  const workspace = input.registry.workspaces?.find((entry) => entry.root === input.workspaceRootAbs);
  if (asString(workspace?.profile)) return asString(workspace?.profile);
  return asString(input.registry.defaultProfile) ?? Object.keys(input.registry.profiles ?? {})[0];
}

export async function inferPlanApiBaseUrlFromProbeConfig(args: {
  workspaceRootAbs: string;
  planName: string;
}): Promise<string | undefined> {
  const probeConfigPathAbs = path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const raw = await fs.readFile(probeConfigPathAbs, "utf8").catch(() => "");
  if (!raw) return undefined;
  let registry: ProbeRegistryFile;
  try {
    registry = JSON.parse(stripBom(raw)) as ProbeRegistryFile;
  } catch {
    return undefined;
  }
  const profileName = resolveProbeProfile({
    registry,
    workspaceRootAbs: args.workspaceRootAbs,
  });
  const probes = profileName ? registry.profiles?.[profileName]?.probes : undefined;
  if (!probes) return undefined;

  const probeId = inferComposeServiceNameCandidates(args.planName).find((candidate) => typeof probes[candidate]?.runtime?.port === "number");
  const port = probeId ? probes[probeId]?.runtime?.port : undefined;
  if (typeof port !== "number" || !Number.isInteger(port) || port <= 0 || port > 65535) {
    return undefined;
  }
  return `http://127.0.0.1:${port}`;
}
