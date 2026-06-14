import type { ProbeRegistry } from "@/config/probe-registry";
import type { ServerConfig } from "@/config/server-config";

export type RouteSynthesisTargetInferenceDeps = {
  config: ServerConfig;
};

export type RouteSynthesisRecipeGenerationDeps = {
  probeBaseUrl: string;
  probeStatusPath: string;
  workspaceRootAbs: string;
  getProbeRegistry?: () => ProbeRegistry | undefined;
};

export type RouteSynthesisHandlerDeps = RouteSynthesisTargetInferenceDeps &
  RouteSynthesisRecipeGenerationDeps;
