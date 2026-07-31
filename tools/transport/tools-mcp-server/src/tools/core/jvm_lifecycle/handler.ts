import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { JvmLifecycleRequestSchema } from "@tools-contracts/jvm-lifecycle";
import { createJvmLifecycleDomain, dispatchJvmLifecycleAction } from "@tools-feature-jvm-lifecycle";
import { JVM_LIFECYCLE_TOOL } from "./contract";

export function registerJvmLifecycleTool(server: McpServer): void {
  const domain = createJvmLifecycleDomain();
  server.registerTool(
    JVM_LIFECYCLE_TOOL.name,
    {
      description: JVM_LIFECYCLE_TOOL.description,
      inputSchema: JVM_LIFECYCLE_TOOL.inputSchema,
    },
    async (rawInput) => {
      const parsed = JvmLifecycleRequestSchema.safeParse(rawInput);
      if (!parsed.success) {
        const structuredContent = {
          resultType: "report",
          status: "blocked",
          reasonCode: "jvm_lifecycle_request_invalid",
          reasonMeta: {
            failedStep: "input_validation",
            issues: parsed.error.issues.map((issue) => ({
              path: issue.path.join("."),
              code: issue.code,
            })),
          },
        };
        return {
          content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
          structuredContent,
        };
      }
      return await dispatchJvmLifecycleAction(domain, parsed.data);
    },
  );
}
