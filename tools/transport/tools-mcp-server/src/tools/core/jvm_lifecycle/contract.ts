import { JvmLifecycleInputSchema, JVM_LIFECYCLE_TOOL_CONTRACT } from "@tools-contracts/jvm-lifecycle";

export const JVM_LIFECYCLE_TOOL = {
  ...JVM_LIFECYCLE_TOOL_CONTRACT,
  inputSchema: JvmLifecycleInputSchema,
} as const;
