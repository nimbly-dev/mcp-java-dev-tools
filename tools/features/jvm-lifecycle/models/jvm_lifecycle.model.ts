import type { JvmLifecycleRequest } from "@tools-contracts/jvm-lifecycle";

export type JvmLifecycleResponse = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

export type JvmCandidate = {
  pid: string;
  identityHint: string | null;
  identitySource: string;
  frameworkHint: "spring_boot_candidate" | "unknown";
  frameworkEvidence: string[];
  processStartEpochMs: number | null;
};

export type JvmLifecycleDomain = {
  listJvms: () => Promise<JvmLifecycleResponse>;
  attach: (
    input: Extract<JvmLifecycleRequest, { action: "attach" }>["input"],
  ) => Promise<JvmLifecycleResponse>;
  deactivate: (
    input: Extract<JvmLifecycleRequest, { action: "deactivate" }>["input"],
  ) => Promise<JvmLifecycleResponse>;
};

export type LifecycleHelperResult = {
  operation: "discover" | "attach" | "deactivate" | "unknown";
  outcome: string;
  reasonCode: string;
  pids: string[];
  candidates: JvmCandidate[];
  nonRestorableClasses: string[];
};
