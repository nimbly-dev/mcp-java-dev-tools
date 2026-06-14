import { RouteSynthesisInputSchema } from "@/models/inputs";

export const ROUTE_SYNTHESIS_TOOL = {
  name: "route_synthesis",
  description:
    "Canonical Route Synthesis MCP Tool for target inference, class method inventory, and executable request recipe generation.",
  inputSchema: RouteSynthesisInputSchema,
} as const;
