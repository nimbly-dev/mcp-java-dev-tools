import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { ProbeRequestSchema } from "@/models/inputs";
import { createProbeDomain } from "@/tools/core/probe/domain";
import { PROBE_TOOL } from "@/tools/core/probe/contract";
import type { ProbeRegistry } from "@/config/probe-registry";

export type ProbeHandlerConfig = {
  probeBaseUrl: string;
  probeStatusPath: string;
  probeResetPath: string;
  probeActuatePath: string;
  probeCapturePath: string;
  probeWaitMaxRetries: number;
  probeWaitUnreachableRetryEnabled: boolean;
  probeWaitUnreachableMaxRetries: number;
  getProbeRegistry?: () => ProbeRegistry | undefined;
};

export function registerProbeTools(server: McpServer, cfg: ProbeHandlerConfig): void {
  const domain = createProbeDomain(cfg);
  server.registerTool(
    PROBE_TOOL.name,
    {
      description: PROBE_TOOL.description,
      inputSchema: PROBE_TOOL.inputSchema,
    },
    async (rawInput) => {
      const parsed = ProbeRequestSchema.safeParse(rawInput);
      if (!parsed.success) {
        const issues = parsed.error.issues.map((issue) => ({
          path: issue.path.join("."),
          code: issue.code,
          message: issue.message,
        }));
        const structuredContent = {
          resultType: "report",
          status: "probe_request_invalid",
          reasonCode: "probe_request_invalid",
          nextActionCode: "probe_request_invalid",
          reason: "probe input schema validation failed",
          reasonMeta: { failedStep: "input_validation", issues },
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }

      switch (parsed.data.action) {
        case "actuate":
          return await domain.enable(parsed.data.input);
        case "capture":
          return await domain.getCapture(parsed.data.input);
        case "check":
          return await domain.check(parsed.data.input);
        case "reset":
          return await domain.reset(parsed.data.input);
        case "status":
          return await domain.getStatus(parsed.data.input);
        case "wait_for_hit":
          return await domain.waitForHit(parsed.data.input);
      }
    },
  );
}
