import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { FailureAnalysisRequestSchema } from "@tools-contracts/failure-analysis";
import {
  createFailureAnalysisDomain,
  dispatchFailureAnalysisAction,
} from "@tools-feature-failure-analysis";
import { FAILURE_ANALYSIS_TOOL } from "./contract";

export function registerFailureAnalysisTool(server: McpServer): void {
  const domain = createFailureAnalysisDomain();
  server.registerTool(
    FAILURE_ANALYSIS_TOOL.name,
    {
      description: FAILURE_ANALYSIS_TOOL.description,
      inputSchema: FAILURE_ANALYSIS_TOOL.inputSchema,
    },
    async (rawInput) => {
      const parsed = FailureAnalysisRequestSchema.safeParse(rawInput);
      if (!parsed.success) return invalidRequest(parsed.error.message);
      return await dispatchFailureAnalysisAction(domain, parsed.data);
    },
  );
}

function invalidRequest(message: string) {
  const structuredContent = {
    outcome: "INCONCLUSIVE",
    reasonCode: "failure_analysis_request_invalid",
    failedStep: "input_validation",
    reason: message,
  };
  return {
    content: [{ type: "text" as const, text: JSON.stringify(structuredContent) }],
    structuredContent,
  };
}
