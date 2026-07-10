import type { SynthesisRecipeCandidate } from "@tools-registry/models/synthesis/synthesizer_output.model";

export type RuntimeMappingsReasonCode =
  | "runtime_mappings_input_required"
  | "runtime_mappings_unreachable"
  | "runtime_mappings_unauthorized"
  | "runtime_mappings_invalid_payload"
  | "runtime_mapping_not_found"
  | "runtime_mapping_ambiguous";

export type RuntimeMappingsResolveInput = {
  mappingsBaseUrl: string;
  classHint: string;
  methodHint: string;
  authToken?: string;
};

export type RuntimeMappingsResolveSuccess = {
  status: "ok";
  requestCandidate: SynthesisRecipeCandidate;
  evidence: string[];
  attemptedStrategies: string[];
};

export type RuntimeMappingsResolveFailure = {
  status: "report";
  reasonCode: RuntimeMappingsReasonCode;
  failedStep: string;
  nextAction: string;
  evidence: string[];
  attemptedStrategies: string[];
};

export type RuntimeMappingsResolveResult =
  | RuntimeMappingsResolveSuccess
  | RuntimeMappingsResolveFailure;
