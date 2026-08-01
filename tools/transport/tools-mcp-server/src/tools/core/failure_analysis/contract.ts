import {
  FailureAnalysisInputSchema,
  FAILURE_ANALYSIS_TOOL_CONTRACT,
} from "@tools-contracts/failure-analysis";

export const FAILURE_ANALYSIS_TOOL = {
  ...FAILURE_ANALYSIS_TOOL_CONTRACT,
  inputSchema: FailureAnalysisInputSchema,
} as const;
