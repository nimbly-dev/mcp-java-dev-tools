import type { RouteSynthesisAction } from "@tools-contracts/route-synthesis";
import type { RouteSynthesisHandlerDeps } from "@tools-feature-route-synthesis";
import { dispatchRouteSynthesisAction } from ".";

export async function routeSynthesisDomain(args: {
  action: RouteSynthesisAction;
  input: Record<string, unknown>;
  deps: RouteSynthesisHandlerDeps;
}): Promise<{
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
}> {
  return dispatchRouteSynthesisAction(args);
}
