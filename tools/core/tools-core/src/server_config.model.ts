import type { ProbeRegistry } from "./probe-registry";

/** Capability-neutral runtime configuration consumed by Feature Modules. */
export type ServerConfig = {
  workspaceRootAbs: string;
  workspaceRootSource: "arg" | "env" | "session" | "cwd" | "probe-config";
  probeBaseUrl: string;
  probeStatusPath: string;
  probeResetPath: string;
  probeCapturePath: string;
  probeLineSelectionMaxScanLines: number;
  probeWaitMaxRetries: number;
  probeWaitUnreachableRetryEnabled: boolean;
  probeWaitUnreachableMaxRetries: number;
  probeRegistry?: ProbeRegistry;
};
