import * as fs from "node:fs";
import * as path from "node:path";

import { CONFIG_DEFAULTS } from "@/config/defaults";

export type ProbeRuntimeMetadata = {
  platform?: string;
  port?: number;
  [key: string]: unknown;
};

export type ProbeConfigEntry = {
  id: string;
  baseUrl: string;
  description?: string;
  workspaceRoot?: string;
  include: string[];
  exclude: string[];
  runtime?: ProbeRuntimeMetadata;
  probeLineSelectionMaxScanLines?: number;
  probeWaitMaxRetries?: number;
  probeWaitUnreachableRetryEnabled?: boolean;
  probeWaitUnreachableMaxRetries?: number;
  probeIncludeExecutionPaths?: boolean;
};

type ProbeGlobalConfig = {
  probeLineSelectionMaxScanLines?: number;
  probeWaitMaxRetries?: number;
  probeWaitUnreachableRetryEnabled?: boolean;
  probeWaitUnreachableMaxRetries?: number;
  probeIncludeExecutionPaths?: boolean;
  allowNonWrappedExecutable?: boolean;
};

type ProbeProfile = {
  global?: ProbeGlobalConfig;
  probes?: Record<string, Record<string, unknown>>;
};

type ProbeRegistryFile = {
  defaultProfile?: string;
  workspaces?: Array<{ root?: string; profile?: string }>;
  profiles?: Record<string, ProbeProfile>;
};

export type ProbeRegistryLoadArgs = {
  filePath: string;
  workspaceRootAbs: string;
  profileOverride?: string;
};

export type ProbeRegistry = {
  configFileAbs: string;
  activeProfile: string;
  profileSource: "env" | "workspace" | "default";
  implicitProbeId?: string;
  probesById: Map<string, ProbeConfigEntry>;
  allowNonWrappedExecutable: boolean;
};

export type ProbeRegistryReloadState = {
  lastReloadAt?: string;
  lastReloadStatus?: "ok" | "error";
  lastReloadError?: string;
};

export type ProbeRegistrySummary = {
  configFileAbs: string;
  activeProfile: string;
  profileSource: "env" | "workspace" | "default";
  implicitProbeId?: string;
  probeCount: number;
  allowNonWrappedExecutable: boolean;
  lastReloadAt?: string;
  lastReloadStatus?: "ok" | "error";
  lastReloadError?: string;
  probes: Array<{
    id: string;
    baseUrl: string;
    description?: string;
    include: string[];
    exclude: string[];
    runtime?: Record<string, unknown>;
  }>;
};

function stripUtf8Bom(raw: string): string {
  if (raw.charCodeAt(0) === 0xfeff) return raw.slice(1);
  return raw;
}

function parseBoolean(raw: unknown): boolean | undefined {
  return typeof raw === "boolean" ? raw : undefined;
}

function parseIntBounded(raw: unknown, min: number, max: number): number | undefined {
  if (typeof raw !== "number" || !Number.isFinite(raw)) return undefined;
  const n = Math.trunc(raw);
  if (n < min) return min;
  if (n > max) return max;
  return n;
}

function mustString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`Invalid probe registry config: ${field} must be a non-empty string.`);
  }
  return value.trim();
}

function toStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== "string") continue;
    const trimmed = item.trim();
    if (!trimmed) continue;
    out.push(trimmed);
  }
  return out;
}

function resolveProfile(args: {
  parsed: ProbeRegistryFile;
  workspaceRootAbs: string;
  profileOverride?: string;
}): { profileName: string; profileSource: "env" | "workspace" | "default" } {
  const profiles = args.parsed.profiles ?? {};
  const profileNames = Object.keys(profiles);
  if (profileNames.length === 0) {
    throw new Error("Invalid probe registry config: profiles must contain at least one profile.");
  }

  if (args.profileOverride) {
    if (!profiles[args.profileOverride]) {
      throw new Error(
        `Probe profile '${args.profileOverride}' not found in probe registry configuration.`,
      );
    }
    return { profileName: args.profileOverride, profileSource: "env" };
  }

  const workspaceCandidates = args.parsed.workspaces ?? [];
  let matchedProfile: string | undefined;
  let matchedRootLen = -1;
  for (const workspace of workspaceCandidates) {
    const rootRaw = workspace.root;
    const profileRaw = workspace.profile;
    if (typeof rootRaw !== "string" || typeof profileRaw !== "string") continue;
    const rootAbs = path.resolve(rootRaw.trim());
    const normalizedRoot = rootAbs.toLowerCase();
    const normalizedWorkspace = args.workspaceRootAbs.toLowerCase();
    const isMatch =
      normalizedWorkspace === normalizedRoot ||
      normalizedWorkspace.startsWith(`${normalizedRoot}${path.sep.toLowerCase()}`);
    if (!isMatch) continue;
    if (rootAbs.length > matchedRootLen) {
      matchedRootLen = rootAbs.length;
      matchedProfile = profileRaw.trim();
    }
  }
  if (matchedProfile) {
    if (!profiles[matchedProfile]) {
      throw new Error(
        `Probe profile '${matchedProfile}' (resolved from workspaces) not found in probe registry configuration.`,
      );
    }
    return { profileName: matchedProfile, profileSource: "workspace" };
  }

  const defaultProfile = args.parsed.defaultProfile?.trim() || profileNames[0];
  if (!defaultProfile || !profiles[defaultProfile]) {
    throw new Error("Invalid probe registry config: unable to resolve default profile.");
  }
  return { profileName: defaultProfile, profileSource: "default" };
}

export function loadProbeRegistry(args: ProbeRegistryLoadArgs): ProbeRegistry {
  const configFileAbs = path.resolve(args.filePath);
  if (!fs.existsSync(configFileAbs)) {
    throw new Error(`Probe registry config file not found: ${configFileAbs}`);
  }
  const raw = fs.readFileSync(configFileAbs, "utf8");
  const parsed = JSON.parse(stripUtf8Bom(raw)) as ProbeRegistryFile;
  const profileResolveArgs: Parameters<typeof resolveProfile>[0] = {
    parsed,
    workspaceRootAbs: args.workspaceRootAbs,
  };
  if (typeof args.profileOverride === "string") {
    profileResolveArgs.profileOverride = args.profileOverride;
  }
  const { profileName, profileSource } = resolveProfile(profileResolveArgs);
  const profile = parsed.profiles?.[profileName];
  if (!profile) {
    throw new Error(`Probe profile '${profileName}' not found in probe registry configuration.`);
  }
  if (Object.prototype.hasOwnProperty.call(profile, "defaultProbe")) {
    throw new Error(
      `Invalid probe registry config: profiles.${profileName}.defaultProbe is not supported. Remove it and use explicit probeId selection for multi-probe profiles.`,
    );
  }

  const global = profile.global ?? {};
  const allowNonWrappedExecutable = global.allowNonWrappedExecutable === true;
  const probeEntries = profile.probes ?? {};
  const probeIds = Object.keys(probeEntries);
  if (probeIds.length === 0) {
    throw new Error(`Probe profile '${profileName}' has no probes configured.`);
  }

  const probesById = new Map<string, ProbeConfigEntry>();
  for (const probeId of probeIds) {
    const entry = probeEntries[probeId] ?? {};
    const baseUrl = mustString(entry.baseUrl, `profiles.${profileName}.probes.${probeId}.baseUrl`);
    const include = toStringArray(entry.include);
    const exclude = toStringArray(entry.exclude);
    const config: ProbeConfigEntry = {
      id: probeId,
      baseUrl,
      include,
      exclude,
      ...(typeof entry.description === "string" ? { description: entry.description.trim() } : {}),
      ...(typeof entry.workspaceRoot === "string"
        ? { workspaceRoot: path.resolve(entry.workspaceRoot.trim()) }
        : {}),
      ...(entry.runtime && typeof entry.runtime === "object"
        ? { runtime: entry.runtime as ProbeRuntimeMetadata }
        : {}),
    };

    const lineScan = parseIntBounded(
      entry.probeLineSelectionMaxScanLines ?? global.probeLineSelectionMaxScanLines,
      CONFIG_DEFAULTS.PROBE_LINE_SELECTION_MAX_SCAN_LINES_MIN,
      CONFIG_DEFAULTS.PROBE_LINE_SELECTION_MAX_SCAN_LINES_MAX,
    );
    if (typeof lineScan === "number") config.probeLineSelectionMaxScanLines = lineScan;

    const waitRetries = parseIntBounded(
      entry.probeWaitMaxRetries ?? global.probeWaitMaxRetries,
      CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES_MIN,
      CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES_MAX,
    );
    if (typeof waitRetries === "number") config.probeWaitMaxRetries = waitRetries;

    const unreachableEnabled = parseBoolean(
      entry.probeWaitUnreachableRetryEnabled ?? global.probeWaitUnreachableRetryEnabled,
    );
    if (typeof unreachableEnabled === "boolean") {
      config.probeWaitUnreachableRetryEnabled = unreachableEnabled;
    }

    const unreachableMax = parseIntBounded(
      entry.probeWaitUnreachableMaxRetries ?? global.probeWaitUnreachableMaxRetries,
      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES_MIN,
      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES_MAX,
    );
    if (typeof unreachableMax === "number") config.probeWaitUnreachableMaxRetries = unreachableMax;

    const includeExecutionPaths = parseBoolean(
      entry.probeIncludeExecutionPaths ?? global.probeIncludeExecutionPaths,
    );
    if (typeof includeExecutionPaths === "boolean") {
      config.probeIncludeExecutionPaths = includeExecutionPaths;
    }

    probesById.set(probeId, config);
  }

  const implicitProbeId = probeIds.length === 1 ? probeIds[0] : undefined;

  return {
    configFileAbs,
    activeProfile: profileName,
    profileSource,
    ...(implicitProbeId ? { implicitProbeId } : {}),
    probesById,
    allowNonWrappedExecutable,
  };
}

export function summarizeProbeRegistry(
  registry: ProbeRegistry,
  reloadState?: ProbeRegistryReloadState,
): ProbeRegistrySummary {
  return {
    configFileAbs: registry.configFileAbs,
    activeProfile: registry.activeProfile,
    profileSource: registry.profileSource,
    ...(registry.implicitProbeId ? { implicitProbeId: registry.implicitProbeId } : {}),
    probeCount: registry.probesById.size,
    allowNonWrappedExecutable: registry.allowNonWrappedExecutable,
    ...(reloadState?.lastReloadAt ? { lastReloadAt: reloadState.lastReloadAt } : {}),
    ...(reloadState?.lastReloadStatus ? { lastReloadStatus: reloadState.lastReloadStatus } : {}),
    ...(reloadState?.lastReloadError ? { lastReloadError: reloadState.lastReloadError } : {}),
    probes: Array.from(registry.probesById.values()).map((probe) => ({
      id: probe.id,
      baseUrl: probe.baseUrl,
      ...(probe.description ? { description: probe.description } : {}),
      include: probe.include,
      exclude: probe.exclude,
      ...(probe.runtime ? { runtime: probe.runtime } : {}),
    })),
  };
}
