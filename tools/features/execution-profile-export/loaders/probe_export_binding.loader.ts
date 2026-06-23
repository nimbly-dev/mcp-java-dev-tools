import { promises as fs } from "node:fs";
import path from "node:path";

import { asString, isRecord } from "@tools-export-execution-profile/common";

function stripUtf8Bom(raw: string): string {
  if (raw.charCodeAt(0) === 0xfeff) {
    return raw.slice(1);
  }
  return raw;
}

export async function resolveProbeBaseUrlForExport(args: {
  workspaceRootAbs: string;
  probeId?: string;
}): Promise<string | undefined> {
  if (!args.probeId || args.probeId.trim().length === 0) {
    return undefined;
  }
  const configPathAbs = path.join(args.workspaceRootAbs, ".mcpjvm", "probe-config.json");
  try {
    const text = await fs.readFile(configPathAbs, "utf8");
    const parsed = JSON.parse(stripUtf8Bom(text)) as unknown;
    if (!isRecord(parsed)) return undefined;
    const profiles = isRecord(parsed.profiles) ? parsed.profiles : undefined;
    if (!profiles) return undefined;
    const defaultProfile = asString(parsed.defaultProfile);
    const activeProfileName =
      resolveWorkspaceProfileName({
        parsed,
        workspaceRootAbs: args.workspaceRootAbs,
      }) ?? defaultProfile;
    if (!activeProfileName) return undefined;
    const activeProfileRaw = profiles[activeProfileName];
    if (!isRecord(activeProfileRaw)) return undefined;
    const probesRaw = activeProfileRaw.probes;
    if (!isRecord(probesRaw)) return undefined;
    const probeRaw = probesRaw[args.probeId];
    if (!isRecord(probeRaw)) return undefined;
    return asString(probeRaw.baseUrl);
  } catch {
    return undefined;
  }
}

function resolveWorkspaceProfileName(args: {
  parsed: Record<string, unknown>;
  workspaceRootAbs: string;
}): string | undefined {
  const workspaces = Array.isArray(args.parsed.workspaces) ? args.parsed.workspaces : [];
  const normalizedWorkspaceRoot = path.resolve(args.workspaceRootAbs).toLowerCase();
  let matchedProfile: string | undefined;
  let matchedRootLen = -1;
  for (const raw of workspaces) {
    if (!isRecord(raw)) continue;
    const root = asString(raw.root);
    const profile = asString(raw.profile);
    if (!root || !profile) continue;
    const rootAbs = path.resolve(root);
    const normalizedRoot = rootAbs.toLowerCase();
    const isMatch =
      normalizedWorkspaceRoot === normalizedRoot ||
      normalizedWorkspaceRoot.startsWith(`${normalizedRoot}${path.sep.toLowerCase()}`);
    if (!isMatch) continue;
    if (rootAbs.length > matchedRootLen) {
      matchedRootLen = rootAbs.length;
      matchedProfile = profile;
    }
  }
  return matchedProfile;
}
