export type SynthesisHttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export type SynthesisRequestInferenceSource = "spring_mvc" | "jaxrs";

export type SynthesisRecipeCandidate = {
  method: SynthesisHttpMethod;
  path: string;
  queryTemplate: string;
  fullUrlHint: string;
  bodyTemplate?: string;
  assumptions?: string[];
  needsConfirmation?: string[];
  rationale: string[];
};

export type SynthesisHttpTrigger = {
  kind: "http";
  method: SynthesisHttpMethod;
  path: string;
  queryTemplate: string;
  fullUrlHint: string;
  bodyTemplate?: string;
  headers: Record<string, string>;
  contentType?: string;
};

export type SynthesizerOutput = {
  status: "recipe";
  synthesizerUsed: string;
  framework: string;
  requestCandidate: SynthesisRecipeCandidate;
  trigger: SynthesisHttpTrigger;
  requestSource?: SynthesisRequestInferenceSource;
  matchedControllerFile?: string;
  matchedBranchCondition?: string;
  matchedRootAbs?: string;
  evidence: string[];
  attemptedStrategies: string[];
};
