import { resolveLifecycleHelperLaunch, runLifecycleHelper } from "../shared/lifecycle_helper";
import type { JvmLifecycleResponse } from "../models/jvm_lifecycle.model";

function response(structuredContent: Record<string, unknown>): JvmLifecycleResponse {
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent, null, 2) }],
    structuredContent,
  };
}

export async function listJvmsAction(): Promise<JvmLifecycleResponse> {
  const launch = resolveLifecycleHelperLaunch();
  if (!launch.ok) {
    return response({ resultType: "report", status: "blocked", reasonCode: launch.reasonCode });
  }
  const result = await runLifecycleHelper(launch.value, ["discover"]);
  if (!("operation" in result) || result.operation !== "discover") {
    return response({
      resultType: "report",
      status: "blocked",
      reasonCode: "reasonCode" in result ? result.reasonCode : "helper_result_invalid",
    });
  }
  const jvms = result.pids
    .filter((pid) => pid !== String(process.pid))
    .slice(0, 128)
    .map((pid) => result.candidates.find((candidate) => candidate.pid === pid))
    .filter((candidate) => candidate !== undefined)
    .map((candidate) => ({
      ...candidate,
      attachmentState: "unverified",
      probeState: "unverified",
    }));
  return response({
    resultType: "jvm_list",
    status: "ok",
    reasonCode: result.reasonCode,
    jvms,
  });
}
