export type NormalizedRecipeGenerateInput = {
  rootAbs: string;
  workspaceRootAbs: string;
  additionalSourceRootsAbs?: string[];
  classHint: string;
  methodHint: string;
  intentMode: "line_probe" | "regression";
  lineHint?: number;
  mappingsBaseUrl?: string;
  discoveryPreference: "static_only" | "runtime_first" | "runtime_only";
  apiBasePath?: string;
  maxCandidates: number;
  authToken?: string;
  authUsername?: string;
  authPassword?: string;
  actuationEnabled: boolean;
  actuationReturnBoolean?: boolean;
  actuationActuatorId?: string;
};

export function normalizeRecipeGenerateInput(args: {
  rootAbs: string;
  workspaceRootAbs: string;
  additionalSourceRootsAbs?: string[];
  classHint: string;
  methodHint: string;
  lineHint?: number;
  mappingsBaseUrl?: string;
  discoveryPreference?: "static_only" | "runtime_first" | "runtime_only";
  apiBasePath?: string;
  intentMode: "line_probe" | "regression";
  maxCandidates?: number;
  authToken?: string;
  authUsername?: string;
  authPassword?: string;
  actuationEnabled?: boolean;
  actuationReturnBoolean?: boolean;
  actuationActuatorId?: string;
}): NormalizedRecipeGenerateInput {
  const normalizedApiBasePath = normalizeApiBasePath(args.apiBasePath);
  const normalizedMappingsBaseUrl = normalizeMappingsBaseUrl(args.mappingsBaseUrl);
  return {
    rootAbs: args.rootAbs,
    workspaceRootAbs: args.workspaceRootAbs,
    ...(args.additionalSourceRootsAbs?.length
      ? { additionalSourceRootsAbs: args.additionalSourceRootsAbs }
      : {}),
    classHint: args.classHint,
    methodHint: args.methodHint,
    intentMode: args.intentMode,
    ...(typeof args.lineHint === "number" ? { lineHint: args.lineHint } : {}),
    ...(normalizedMappingsBaseUrl ? { mappingsBaseUrl: normalizedMappingsBaseUrl } : {}),
    discoveryPreference: args.discoveryPreference ?? "static_only",
    ...(normalizedApiBasePath ? { apiBasePath: normalizedApiBasePath } : {}),
    maxCandidates: typeof args.maxCandidates === "number" ? Math.max(1, args.maxCandidates) : 1,
    ...(args.authToken ? { authToken: args.authToken } : {}),
    ...(args.authUsername ? { authUsername: args.authUsername } : {}),
    ...(args.authPassword ? { authPassword: args.authPassword } : {}),
    actuationEnabled: args.actuationEnabled === true,
    ...(typeof args.actuationReturnBoolean === "boolean"
      ? { actuationReturnBoolean: args.actuationReturnBoolean }
      : {}),
    ...(args.actuationActuatorId ? { actuationActuatorId: args.actuationActuatorId } : {}),
  };
}

function normalizeMappingsBaseUrl(value?: string): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed;
}

function normalizeApiBasePath(value?: string): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const withLeadingSlash = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  const withoutTrailingSlash =
    withLeadingSlash.length > 1 ? withLeadingSlash.replace(/\/+$/, "") : withLeadingSlash;
  return withoutTrailingSlash || "/";
}
