import { executeHttpTransportRequest } from "@tools-feature-transport-execution";
import type { ProbeDomainConfig } from "@tools-feature-probe";
import { createProbeDomain } from "@tools-feature-probe";

type SuiteToolInput = {
  toolName: string;
  input: Record<string, unknown>;
};

export type ExecutionOrchestrationSuiteToolInvoker = (
  args: SuiteToolInput,
) => Promise<{ structuredContent: Record<string, unknown> }>;

export function createSuiteToolInvoker(args: {
  probeConfig: ProbeDomainConfig;
}): ExecutionOrchestrationSuiteToolInvoker {
  const probeDomain = createProbeDomain(args.probeConfig);
  return async ({ toolName, input: toolInput }) => {
    if (toolName === "transport_execute") {
      if (
        toolInput.wrappedOnly !== false &&
        (args.probeConfig.getProbeRegistry?.()?.allowNonWrappedExecutable ?? false)
      ) {
        return {
          structuredContent: {
            status: "blocked_invalid",
            reasonCode: "wrapper_policy_violation",
            requiredUserAction: [
              "Disable non-wrapped executable transport in probe registry or do not require wrappedOnly execution.",
            ],
          },
        };
      }
      return {
        structuredContent: await executeHttpTransportRequest({
          request: toolInput.request as Record<string, unknown>,
          includeBody: true,
        }),
      };
    }
    if (toolName === "probe") {
      const action = toolInput.action;
      const probeInput =
        typeof toolInput.input === "object" &&
        toolInput.input !== null &&
        !Array.isArray(toolInput.input)
          ? (toolInput.input as Record<string, unknown>)
          : undefined;
      if (action === "reset" && probeInput) {
        const result = await probeDomain.reset(
          probeInput as Parameters<typeof probeDomain.reset>[0],
        );
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      if (action === "status" && probeInput) {
        const result = await probeDomain.getStatus(
          probeInput as Parameters<typeof probeDomain.getStatus>[0],
        );
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      if (action === "wait_for_hit" && probeInput) {
        const result = await probeDomain.waitForHit(
          probeInput as Parameters<typeof probeDomain.waitForHit>[0],
        );
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
      if (action === "profiler" && probeInput) {
        const result = await probeDomain.profiler(
          probeInput as Parameters<typeof probeDomain.profiler>[0],
        );
        return { structuredContent: result.structuredContent as Record<string, unknown> };
      }
    }
    return {
      structuredContent: {
        status: "blocked_invalid",
        reasonCode: "toolchain_unavailable",
        requiredUserAction: [`Unsupported suite tool invocation: ${toolName}`],
      },
    };
  };
}
