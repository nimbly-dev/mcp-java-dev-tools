export type ProbeExecutionPaths = string[];

export type ProbeCapturePreviewPayload =
  | {
      available: true;
      captureId?: string;
      capturedAtEpoch?: number;
      executionStartedAtEpoch?: number;
      executionEndedAtEpoch?: number;
      executionDurationMs?: number;
      threadAllocatedBytesDelta?: number;
      executionPaths?: ProbeExecutionPaths;
      [key: string]: unknown;
    }
  | {
      available?: false;
      [key: string]: unknown;
    };

export interface ProbeCaptureRecordPayload {
  captureId?: string;
  methodKey?: string;
  capturedAtEpoch?: number;
  executionStartedAtEpoch?: number;
  executionEndedAtEpoch?: number;
  executionDurationMs?: number;
  threadAllocatedBytesDelta?: number;
  executionPaths?: ProbeExecutionPaths;
  [key: string]: unknown;
}
