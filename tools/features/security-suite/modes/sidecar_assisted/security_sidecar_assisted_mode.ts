import type { SecurityModeExecutionResult } from "../../models/security_suite.model";

export function executeSidecarAssistedSecurityMode(args: {
  planName: string;
}): SecurityModeExecutionResult {
  return {
    status: "blocked",
    reasonCode: "security_mode_execution_not_implemented",
    requiredUserAction: [
      "Install the Sidecar-assisted Security Mode implementation from the follow-on security mode ticket before executing this plan.",
    ],
    reasonMeta: { securityMode: "sidecar_assisted", planName: args.planName },
  };
}
