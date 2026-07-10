import type {
  SynthesisHttpMethod,
  SynthesisRecipeCandidate,
  SynthesisRequestInferenceSource,
} from "@tools-registry/models/synthesis/synthesizer_output.model";

export type SpringParamType = {
  name: string;
  requestName: string;
  javaType?: string;
};

export type SpringControllerParam = SpringParamType & {
  source: "query" | "path" | "header" | "body" | "unknown";
};

export type SpringMethodCallContext = {
  line: number;
  contextLines: string[];
  argNames: string[];
  enclosingMethodName?: string;
};

export type SpringControllerRequestMatch = {
  recipe?: SynthesisRecipeCandidate;
  requestSource?: SynthesisRequestInferenceSource;
  matchedControllerFile?: string;
  matchedBranchCondition?: string;
  matchedRootAbs?: string;
};

export type SpringCallerMethodCandidate = {
  methodName: string;
  fileAbs: string;
  score: number;
};

export type SpringEndpointMapping = {
  method: SynthesisHttpMethod;
  path: string;
  source: SynthesisRequestInferenceSource;
};
