export type FailureReasonMeta = Record<string, unknown>;

export type FailureDiagnostics = {
  reasonCode: string;
  nextActionCode: string;
  reasonMeta?: FailureReasonMeta;
};

