import * as z from "zod/v4";
import { ProjectScopedInputSchema } from "@/models/inputs/artifact_management/shared/common.model";

export const ExecutionExportInputSchema = ProjectScopedInputSchema.extend({
  mode: z.enum(["ps1", "sh", "postman"]).optional(),
  query: z
    .object({
      exportId: z.string().optional(),
      select: z.array(z.string()).optional(),
    })
    .optional(),
  planName: z.string().optional(),
  executionProfile: z.string().optional(),
  when: z.string().optional(),
  includeResolvedSecrets: z.boolean().optional(),
  includeRuntimeStartup: z.boolean().optional(),
  includeHealthcheckGate: z.boolean().optional(),
  contextBindings: z.record(z.string(), z.string()).optional(),
  contextValues: z.record(z.string(), z.string()).optional(),
});

export type ExecutionExportInput = z.infer<typeof ExecutionExportInputSchema>;
