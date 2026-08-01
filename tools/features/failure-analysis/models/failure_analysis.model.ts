import type { FailureAnalysisRequest } from "@tools-contracts/failure-analysis";

export type FailureAnalysisResponse = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

export type FailureAnalysisDomain = {
  analyzeTrace: (
    input: Extract<FailureAnalysisRequest, { action: "analyze_trace" }>["input"],
  ) => Promise<FailureAnalysisResponse>;
  verifyReproduction: (
    input: Extract<FailureAnalysisRequest, { action: "verify_reproduction" }>["input"],
  ) => Promise<FailureAnalysisResponse>;
};
