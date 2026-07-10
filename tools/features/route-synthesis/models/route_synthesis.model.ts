import type { ProbeRegistry } from "@tools-core/probe-registry";
import type { ServerConfig } from "@tools-core/server_config.model";

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
