export type ToolTextResponse = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

/** Canonical MCP response wrapper. The field names and nesting are stable. */
export type McpToolOutput = ToolTextResponse;

/** Canonical action envelope shared by consolidated MCP Tools. */
export type McpActionEnvelope<Action, Input> = {
  action: Action;
  input: Input;
};

export type ArtifactActionEnvelope<ArtifactType, Action, Input> = {
  artifactType: ArtifactType;
  action: Action;
  input: Input;
};
