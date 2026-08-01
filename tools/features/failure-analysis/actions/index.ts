import type { FailureAnalysisRequest } from "@tools-contracts/failure-analysis";

import { analyzeTraceAction } from "./analyze_trace.action";
import { verifyReproductionAction } from "./verify_reproduction.action";
import type {
  FailureAnalysisDomain,
  FailureAnalysisResponse,
} from "../models/failure_analysis.model";

export function createFailureAnalysisDomain(): FailureAnalysisDomain {
  return { analyzeTrace: analyzeTraceAction, verifyReproduction: verifyReproductionAction };
}

export async function dispatchFailureAnalysisAction(
  domain: FailureAnalysisDomain,
  request: FailureAnalysisRequest,
): Promise<FailureAnalysisResponse> {
  switch (request.action) {
    case "analyze_trace":
      return await domain.analyzeTrace(request.input);
    case "verify_reproduction":
      return await domain.verifyReproduction(request.input);
  }
}
