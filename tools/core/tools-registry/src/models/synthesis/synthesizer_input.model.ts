export type SynthesizerIntentMode =
  | "regression"
  | "single_line_probe"
  | "regression_plus_line_probe";

export type SynthesizerInput = {
  rootAbs: string;
  workspaceRootAbs: string;
  searchRootsAbs: string[];
  classHint: string;
  methodHint: string;
  intentMode: SynthesizerIntentMode;
  lineHint?: number;
  inferredTargetFileAbs?: string;
  mappingsBaseUrl?: string;
  discoveryPreference?: "static_only" | "runtime_first" | "runtime_only";
  authToken?: string;
};

