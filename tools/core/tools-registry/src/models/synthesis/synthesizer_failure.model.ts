export type SynthesizerFailureReasonCode =
  | "synthesizer_not_installed"
  | "framework_not_supported"
  | "ast_resolver_unavailable"
  | "project_root_invalid"
  | "target_type_not_found"
  | "target_type_ambiguous"
  | "target_method_not_found"
  | "mapper_plugin_unavailable"
  | "runtime_mappings_input_required"
  | "runtime_mappings_unreachable"
  | "runtime_mappings_unauthorized"
  | "runtime_mappings_invalid_payload"
  | "runtime_mapping_not_found"
  | "runtime_mapping_ambiguous"
  | "spring_entrypoint_not_proven"
  | "spring_mapping_not_proven"
  | "spring_gateway_route_not_found"
  | "spring_gateway_route_ambiguous"
  | "request_candidate_missing";

export type SynthesizerFailure = {
  status: "report";
  reasonCode: SynthesizerFailureReasonCode;
  failedStep: string;
  nextAction: string;
  evidence: string[];
  attemptedStrategies: string[];
  synthesizerUsed?: string;
};
