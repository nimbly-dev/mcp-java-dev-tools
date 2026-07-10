/**
 * Stable reason-code contract shared by MCP Tools and Feature Modules.
 *
 * Feature-specific reason codes remain owned by their Feature Module until
 * they are intentionally promoted as a cross-feature contract.
 */
export type ReasonCode = string;

export const COMMON_REASON_CODES = {
  ok: "ok",
  invalidInput: "invalid_input",
  actionNotAllowed: "action_not_allowed",
  blocked: "blocked",
} as const;

export type CommonReasonCode = (typeof COMMON_REASON_CODES)[keyof typeof COMMON_REASON_CODES];

export const MCP_REQUEST_REASON_CODES = {
  probeInvalid: "probe_request_invalid",
  artifactInvalid: "artifact_request_invalid",
  executionInvalid: "execution_request_invalid",
  routeSynthesisInvalid: "route_synthesis_request_invalid",
} as const;

export type McpReasonCode = string;
