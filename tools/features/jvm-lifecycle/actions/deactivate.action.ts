import type { JvmLifecycleRequest } from "@tools-contracts/jvm-lifecycle";

import type { JvmLifecycleResponse } from "../models/jvm_lifecycle.model";
import { resolveAgentJar, resolveLifecycleHelperLaunch, runLifecycleHelper } from "../shared/lifecycle_helper";

type DeactivateInput = Extract<JvmLifecycleRequest, { action: "deactivate" }>["input"];

function response(structuredContent: Record<string, unknown>): JvmLifecycleResponse {
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

export async function deactivateAction(input: DeactivateInput): Promise<JvmLifecycleResponse> {
  if (input.pid === String(process.pid)) {
    return response({ resultType: "report", status: "blocked", reasonCode: "mcp_server_attach_forbidden" });
  }
  const launch = resolveLifecycleHelperLaunch();
  if (!launch.ok) {
    return response({ resultType: "report", status: "blocked", reasonCode: launch.reasonCode });
  }
  const agentJar = resolveAgentJar();
  if (!agentJar.ok) {
    return response({ resultType: "report", status: "blocked", reasonCode: agentJar.reasonCode });
  }
  const result = await runLifecycleHelper(launch.value, [
    "deactivate",
    "--pid",
    input.pid,
    "--agent-jar",
    agentJar.value,
    "--confirm",
    "true",
  ]);
  if (!("operation" in result) || result.operation !== "deactivate") {
    return response({
      resultType: "report",
      status: "blocked",
      reasonCode: "reasonCode" in result ? result.reasonCode : "helper_result_invalid",
    });
  }
  const deactivated = result.outcome === "deactivated" || result.outcome === "partial";
  return response({
    resultType: "jvm_lifecycle",
    status: deactivated ? "ok" : "blocked",
    reasonCode: result.reasonCode,
    selectedJvm: { pid: input.pid },
    lifecycle: { operation: result.operation, outcome: result.outcome },
    nonRestorableClasses: result.nonRestorableClasses,
  });
}
