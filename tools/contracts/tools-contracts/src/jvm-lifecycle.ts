import * as z from "zod/v4";

export const JVM_LIFECYCLE_TOOL_CONTRACT = {
  name: "jvm_lifecycle",
  description:
    "Discover local JVMs and safely attach or deactivate the repository-owned Sidecar Agent.",
} as const;

const NumericPidSchema = z.string().regex(/^[1-9][0-9]*$/, "pid must be a positive numeric string");
const ProbeHostSchema = z.string().trim().min(1).max(255);
const ProbePortSchema = z.number().int().min(1).max(65_535);

const ListJvmsInputSchema = z.object({}).strict();
const AttachInputSchema = z
  .object({
    pid: NumericPidSchema,
    expectedProcessStartEpochMs: z.number().int().positive(),
    confirm: z.literal(true),
    probeHost: ProbeHostSchema.optional(),
    probePort: ProbePortSchema.optional(),
    include: z.string().trim().min(1).max(2_048).optional(),
    exclude: z.string().trim().min(1).max(2_048).optional(),
  })
  .strict();
const DeactivateInputSchema = z
  .object({
    pid: NumericPidSchema,
    expectedProcessStartEpochMs: z.number().int().positive(),
    confirm: z.literal(true),
  })
  .strict();

export const JvmLifecycleRequestSchema = z.discriminatedUnion("action", [
  z.object({ action: z.literal("list_jvms"), input: ListJvmsInputSchema }),
  z.object({ action: z.literal("attach"), input: AttachInputSchema }),
  z.object({ action: z.literal("deactivate"), input: DeactivateInputSchema }),
]);

export const JvmLifecycleInputSchema = {
  action: z.enum(["list_jvms", "attach", "deactivate"]),
  input: z.union([ListJvmsInputSchema, AttachInputSchema, DeactivateInputSchema]),
} as const;

export type JvmLifecycleRequest = z.infer<typeof JvmLifecycleRequestSchema>;
export type JvmLifecycleAction = JvmLifecycleRequest["action"];
