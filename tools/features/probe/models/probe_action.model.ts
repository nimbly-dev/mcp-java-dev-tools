import type { ProbeRegistry } from "@tools-core/probe-registry";

export type ProbeDomainConfig = {
  probeBaseUrl: string;
  probeStatusPath: string;
  probeResetPath: string;
  probeActuatePath: string;
  probeCapturePath: string;
  probeProfilerPath: string;
  probeWaitMaxRetries: number;
  probeWaitUnreachableRetryEnabled: boolean;
  probeWaitUnreachableMaxRetries: number;
  getProbeRegistry?: () => ProbeRegistry | undefined;
};

export type ProbeEnableInput = { baseUrl?: string; probeId?: string; action: "arm" | "disarm"; sessionId: string; actuatorId?: string; targetKey?: string; returnBoolean?: boolean; ttlMs?: number; timeoutMs?: number };
export type ProbeCheckInput = { baseUrl?: string; probeId?: string; http?: { headers?: Record<string, string> }; timeoutMs?: number };
export type ProbeGetCaptureInput = { captureId: string; baseUrl?: string; probeId?: string; timeoutMs?: number };
export type ProbeGetStatusInput = { key?: string; keys?: string[]; lineHint?: number; baseUrl?: string; probeId?: string; timeoutMs?: number };
export type ProbeResetInput = { key?: string; keys?: string[]; className?: string; lineHint?: number; baseUrl?: string; probeId?: string; timeoutMs?: number };
export type ProbeWaitForHitInput = { key: string; lineHint?: number; baseUrl?: string; probeId?: string; timeoutMs?: number; pollIntervalMs?: number; maxRetries?: number };
export type ProbeProfilerInput = { action: "start" | "stop" | "reset" | "status" | "download"; sessionId?: string; event?: string; intervalNanos?: number; outputPath?: string; outputFormat?: "jfr"; baseUrl?: string; probeId?: string; timeoutMs?: number };

export type ProbeActionRequest =
  | { action: "actuate"; input: ProbeEnableInput }
  | { action: "capture"; input: ProbeGetCaptureInput }
  | { action: "check"; input: ProbeCheckInput }
  | { action: "reset"; input: ProbeResetInput }
  | { action: "status"; input: ProbeGetStatusInput }
  | { action: "wait_for_hit"; input: ProbeWaitForHitInput }
  | { action: "profiler"; input: ProbeProfilerInput };
