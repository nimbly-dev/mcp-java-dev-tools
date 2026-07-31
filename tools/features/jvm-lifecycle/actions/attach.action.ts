import type { JvmLifecycleRequest } from "@tools-contracts/jvm-lifecycle";

import type { JvmLifecycleResponse } from "../models/jvm_lifecycle.model";
import { resolveAgentJar, resolveLifecycleHelperLaunch, runLifecycleHelper } from "../shared/lifecycle_helper";

type AttachInput = Extract<JvmLifecycleRequest, { action: "attach" }>["input"];

function response(structuredContent: Record<string, unknown>): JvmLifecycleResponse {
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

function isAllowedProbeHost(host: string): boolean {
  const normalized = host.toLowerCase();
  if (normalized === "127.0.0.1" || normalized === "localhost" || normalized === "::1") {
    return true;
  }
  const configured = process.env.MCP_JVM_LIFECYCLE_ALLOWED_PROBE_HOSTS ?? "";
  return configured
    .split(",")
    .map((entry) => entry.trim().toLowerCase())
    .filter(Boolean)
    .includes(normalized);
}

export async function attachAction(input: AttachInput): Promise<JvmLifecycleResponse> {
  if (input.pid === String(process.pid)) {
    return response({ resultType: "report", status: "blocked", reasonCode: "mcp_server_attach_forbidden" });
  }
  const probeHost = input.probeHost ?? "127.0.0.1";
  if (!isAllowedProbeHost(probeHost)) {
    return response({ resultType: "report", status: "blocked", reasonCode: "probe_host_not_allowed" });
  }
  const launch = resolveLifecycleHelperLaunch();
  if (!launch.ok) {
    return response({ resultType: "report", status: "blocked", reasonCode: launch.reasonCode });
  }
  const agentJar = resolveAgentJar();
  if (!agentJar.ok) {
    return response({ resultType: "report", status: "blocked", reasonCode: agentJar.reasonCode });
  }
  const probePort = input.probePort ?? 9_191;
  const agentArgs = [`host=${probeHost}`, `port=${probePort}`];
  if (input.include) agentArgs.push(`include=${input.include}`);
  if (input.exclude) agentArgs.push(`exclude=${input.exclude}`);
  const result = await runLifecycleHelper(launch.value, [
    "attach",
    "--pid",
    input.pid,
    "--agent-jar",
    agentJar.value,
    "--confirm",
    "true",
    "--agent-args",
    agentArgs.join(";"),
  ]);
  if (!("operation" in result) || result.operation !== "attach") {
    return response({
      resultType: "report",
      status: "blocked",
      reasonCode: "reasonCode" in result ? result.reasonCode : "helper_result_invalid",
    });
  }
  const active = result.outcome === "active";
  return response({
    resultType: "jvm_lifecycle",
    status: active ? "ok" : "blocked",
    reasonCode: result.reasonCode,
    selectedJvm: { pid: input.pid },
    lifecycle: { operation: result.operation, outcome: result.outcome },
    probe: { baseUrl: `http://${probeHost}:${probePort}`, verification: "pending" },
  });
}
