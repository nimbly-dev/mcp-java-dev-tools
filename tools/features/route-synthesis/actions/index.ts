import type { RouteSynthesisAction } from "@tools-contracts/route-synthesis";
import type { RouteSynthesisHandlerDeps } from "../models/route_synthesis.model";
import { runClassMethods } from "./class_methods.action";
import { runRecipeCreate } from "./create_recipe.action";
import { runTargetInfer } from "./infer_target.action";

export type RouteSynthesisActionMap = Readonly<Record<RouteSynthesisAction, unknown>>;

export function dispatchRouteSynthesisAction(args: {
  action: RouteSynthesisAction;
  input: Record<string, unknown>;
  deps: RouteSynthesisHandlerDeps;
}) {
  switch (args.action) {
    case "infer_target":
      return runTargetInfer({ ...args.input, discoveryMode: "ranked_candidates" }, args.deps);
    case "class_methods":
      return runClassMethods(args.input, args.deps);
    case "create_recipe":
      return runRecipeCreate(args.input, args.deps);
  }
}
