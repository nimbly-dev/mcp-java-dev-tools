import { RouteSynthesisInputSchema } from "@/models/inputs";
import { ROUTE_SYNTHESIS_TOOL_CONTRACT } from "@tools-contracts/route-synthesis";

export const ROUTE_SYNTHESIS_TOOL = {
  ...ROUTE_SYNTHESIS_TOOL_CONTRACT,
  inputSchema: RouteSynthesisInputSchema,
} as const;
