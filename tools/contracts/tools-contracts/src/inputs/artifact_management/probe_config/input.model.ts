import * as z from "zod/v4";

export const ProbeConfigInputSchema = z.object({
  payload: z.record(z.string(), z.unknown()).optional(),
});

export type ProbeConfigInput = z.infer<typeof ProbeConfigInputSchema>;
