import * as z from "zod/v4";

import { ProbeActuateInputSchema } from "@/models/inputs/probe_actuate.input.model";
import { ProbeCaptureGetInputSchema } from "@/models/inputs/probe_capture_get.input.model";
import { ProbeDiagnoseInputSchema } from "@/models/inputs/probe_diagnose.input.model";
import { ProbeProfilerInputSchema } from "@/models/inputs/probe_profiler.input.model";
import { ProbeResetInputSchema } from "@/models/inputs/probe_reset.input.model";
import { ProbeStatusInputSchema } from "@/models/inputs/probe_status.input.model";
import { ProbeWaitHitInputSchema } from "@/models/inputs/probe_wait_hit.input.model";
import { ProbeActionSchema } from "@/models/inputs/probe/shared/actions.model";

const ProbeCheckPayloadSchema = z.object(ProbeDiagnoseInputSchema).strict();
const ProbeStatusPayloadSchema = z.object(ProbeStatusInputSchema).strict();
const ProbeResetPayloadSchema = z.object(ProbeResetInputSchema).strict();
const ProbeWaitForHitPayloadSchema = z.object(ProbeWaitHitInputSchema).strict();
const ProbeCapturePayloadSchema = z.object(ProbeCaptureGetInputSchema).strict();
const ProbeActuatePayloadSchema = z.object(ProbeActuateInputSchema).strict();
const ProbeProfilerPayloadSchema = z.object(ProbeProfilerInputSchema).strict();

export const ProbeRequestSchema = z.discriminatedUnion("action", [
  z.object({
    action: z.literal("check"),
    input: ProbeCheckPayloadSchema,
  }),
  z.object({
    action: z.literal("status"),
    input: ProbeStatusPayloadSchema,
  }),
  z.object({
    action: z.literal("reset"),
    input: ProbeResetPayloadSchema,
  }),
  z.object({
    action: z.literal("wait_for_hit"),
    input: ProbeWaitForHitPayloadSchema,
  }),
  z.object({
    action: z.literal("capture"),
    input: ProbeCapturePayloadSchema,
  }),
  z.object({
    action: z.literal("actuate"),
    input: ProbeActuatePayloadSchema,
  }),
  z.object({
    action: z.literal("profiler"),
    input: ProbeProfilerPayloadSchema,
  }),
]);

export type ProbeRequest = z.infer<typeof ProbeRequestSchema>;

export const ProbeInputSchema = {
  action: ProbeActionSchema.describe("Requested live Probe action."),
  input: z.union([
    ProbeCheckPayloadSchema,
    ProbeStatusPayloadSchema,
    ProbeResetPayloadSchema,
    ProbeWaitForHitPayloadSchema,
    ProbeCapturePayloadSchema,
    ProbeActuatePayloadSchema,
    ProbeProfilerPayloadSchema,
  ]),
} as const;
