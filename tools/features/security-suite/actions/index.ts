import { executeSecurityRuntimeSuite } from "./execute_security_runtime_suite.action";
import type { ExecuteSecurityRuntimeSuiteArgs } from "../models/security_suite.model";

export type SecuritySuiteRequest = {
  action: "execute";
  input: ExecuteSecurityRuntimeSuiteArgs;
};

export type SecuritySuiteActionMap = Readonly<Record<"execute", typeof executeSecurityRuntimeSuite>>;

export function dispatchSecuritySuiteAction(
  request: SecuritySuiteRequest,
): ReturnType<typeof executeSecurityRuntimeSuite> {
  return executeSecurityRuntimeSuite(request.input);
}
