import type {
  SynthesisRecipeCandidate,
  SynthesisRequestInferenceSource,
} from "@tools-registry/models/synthesis/synthesizer_output.model";

export type JvmAstRequestMappingReasonCode =
  | "ast_resolver_unavailable"
  | "project_root_invalid"
  | "target_type_not_found"
  | "target_type_ambiguous"
  | "target_method_not_found"
  | "mapper_plugin_unavailable"
  | "request_mapping_not_proven";

export type JvmAstRequestMappingInput = {
  projectRootAbs: string;
  searchRootsAbs?: string[];
  classHint: string;
  methodHint: string;
  lineHint?: number;
  inferredTargetFileAbs?: string;
};

export type JvmAstRequestMappingSuccess = {
  status: "ok";
  contractVersion: string;
  framework: string;
  requestSource: SynthesisRequestInferenceSource;
  requestCandidate: SynthesisRecipeCandidate;
  matchedTypeFile: string;
  matchedRootAbs: string;
  evidence: string[];
  attemptedStrategies: string[];
  extensions?: Record<string, unknown>;
};

export type JvmAstRequestMappingFailure = {
  status: "report";
  contractVersion: string;
  reasonCode: JvmAstRequestMappingReasonCode;
  failedStep: string;
  nextAction: string;
  evidence: string[];
  attemptedStrategies: string[];
  framework?: string;
  extensions?: Record<string, unknown>;
};

export type JvmAstRequestMappingResult =
  | JvmAstRequestMappingSuccess
  | JvmAstRequestMappingFailure;
