import type { ToolTextResponse } from "@tools-contracts/output";
import { joinUrl } from "../../probe.util";
import { probeStatus } from "../probe_status.util";
import { readProbeUnreachableErrorMessage, sleep } from "../wait_policy.util";

export type ProbeStatusUnreachableDetails = {
  endpoint: string;
  lastError: string;
  unreachableAttempts: number;
  unreachableMaxRetries: number;
  unreachableRetryEnabled: boolean;
};

export type ProbeStatusWithUnreachablePolicyResult =
  | { kind: "status"; response: ToolTextResponse }
  | { kind: "unreachable"; details: ProbeStatusUnreachableDetails };

export async function probeStatusWithUnreachablePolicy(args: {
  key: string;
  baseUrl: string;
  statusPath: string;
  timeoutMs: number;
  pollIntervalMs: number;
  unreachableRetryEnabled: boolean;
  unreachableMaxRetries: number;
}): Promise<ProbeStatusWithUnreachablePolicyResult> {
  const endpointUrl = new URL(joinUrl(args.baseUrl, args.statusPath));
  endpointUrl.searchParams.set("key", args.key);
  const maxAttempts = args.unreachableRetryEnabled ? args.unreachableMaxRetries : 1;
  let lastError = "Probe endpoint unreachable";

  for (let unreachableAttempt = 1; unreachableAttempt <= maxAttempts; unreachableAttempt++) {
    try {
      const response = await probeStatus({
        key: args.key,
        baseUrl: args.baseUrl,
        statusPath: args.statusPath,
        timeoutMs: args.timeoutMs,
      });
      return { kind: "status", response };
    } catch (err) {
      const unreachableError = readProbeUnreachableErrorMessage(err);
      if (!unreachableError) throw err;
      lastError = unreachableError;
      if (unreachableAttempt >= maxAttempts) {
        return {
          kind: "unreachable",
          details: {
            endpoint: endpointUrl.toString(),
            lastError,
            unreachableAttempts: unreachableAttempt,
            unreachableMaxRetries: maxAttempts,
            unreachableRetryEnabled: args.unreachableRetryEnabled,
          },
        };
      }
      await sleep(args.pollIntervalMs);
    }
  }

  return {
    kind: "unreachable",
    details: {
      endpoint: endpointUrl.toString(),
      lastError,
      unreachableAttempts: maxAttempts,
      unreachableMaxRetries: maxAttempts,
      unreachableRetryEnabled: args.unreachableRetryEnabled,
    },
  };
}
