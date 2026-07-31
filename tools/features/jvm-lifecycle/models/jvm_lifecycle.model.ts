import type { JvmLifecycleRequest } from "@tools-contracts/jvm-lifecycle";

export type JvmLifecycleResponse = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

export type JvmLifecycleDomain = {
  listJvms: () => Promise<JvmLifecycleResponse>;
  attach: (input: Extract<JvmLifecycleRequest, { action: "attach" }> ["input"]) => Promise<JvmLifecycleResponse>;
  deactivate: (
    input: Extract<JvmLifecycleRequest, { action: "deactivate" }> ["input"],
  ) => Promise<JvmLifecycleResponse>;
};

export type LifecycleHelperResult = {
  operation: "discover" | "attach" | "deactivate" | "unknown";
  outcome: string;
  reasonCode: string;
  pids: string[];
  nonRestorableClasses: string[];
};
