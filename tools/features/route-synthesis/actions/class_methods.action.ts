import {
  runTargetInfer,
} from "./infer_target.action";
import type { RouteSynthesisTargetInferenceDeps } from "@tools-feature-route-synthesis";

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
