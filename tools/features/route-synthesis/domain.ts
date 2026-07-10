import type { RouteSynthesisAction } from "@/models/inputs";
import type { RouteSynthesisHandlerDeps } from "@/models/route_synthesis.model";
import { runClassMethods } from "@tools-feature-route-synthesis/actions/class_methods.action";
import { runRecipeCreate } from "@tools-feature-route-synthesis/actions/create_recipe.action";
import { runTargetInfer } from "@tools-feature-route-synthesis/actions/infer_target.action";

export async function routeSynthesisDomain(args: {
  action: RouteSynthesisAction;
  input: Record<string, unknown>;
  deps: RouteSynthesisHandlerDeps;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  if (args.action === "infer_target") {
    return await runTargetInfer(
      {
        ...args.input,
        discoveryMode: "ranked_candidates",
      },
      args.deps,
    );
  }

  if (args.action === "class_methods") {
    return await runClassMethods(args.input, args.deps);
  }

  return await runRecipeCreate(args.input, args.deps);
}
