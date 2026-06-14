import {
  runTargetInfer,
} from "@/tools/core/route_synthesis/actions/infer_target.action";
import type { RouteSynthesisTargetInferenceDeps } from "@/models/route_synthesis.model";

export async function runClassMethods(
  input: Record<string, unknown>,
  deps: RouteSynthesisTargetInferenceDeps,
): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  return await runTargetInfer(
    {
      ...input,
      discoveryMode: "class_methods",
    },
    deps,
  );
}
