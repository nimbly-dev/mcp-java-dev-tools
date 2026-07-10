import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { ProbeRequestSchema } from "@tools-contracts/probe";
import { MCP_REQUEST_REASON_CODES } from "@tools-contracts/reason-codes";
import { createProbeDomain, executeProbeAction, type ProbeActionRequest } from "@tools-feature-probe";
import { PROBE_TOOL } from "@/tools/core/probe/contract";
import type { ProbeRegistry } from "@/config/probe-registry";

export type ProbeHandlerConfig = {
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
          status: MCP_REQUEST_REASON_CODES.probeInvalid,
          reasonCode: MCP_REQUEST_REASON_CODES.probeInvalid,
          nextActionCode: MCP_REQUEST_REASON_CODES.probeInvalid,
          reason: "probe input schema validation failed",
          reasonMeta: { failedStep: "input_validation", issues },
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }

      return await executeProbeAction(domain, parsed.data as ProbeActionRequest);
    },
  );
}
