import type { SecurityModeExecutionResult } from "../../models/security_suite.model";

export function executeBlackboxSecurityMode(args: {
  planName: string;
}): SecurityModeExecutionResult {
  return {
    status: "blocked",
    reasonCode: "security_mode_execution_not_implemented",
    requiredUserAction: [
      "Install the Black-box Security Mode implementation from the follow-on security mode ticket before executing this plan.",
    ],
    reasonMeta: { securityMode: "blackbox", planName: args.planName },
  };
}
