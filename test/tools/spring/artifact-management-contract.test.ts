const assert = require("node:assert/strict");
const test = require("node:test");

const { registerArtifactManagementTool } = require("@/tools/core/artifact_management/handler");

type RegisteredToolHandler = (input: Record<string, unknown>) => Promise<{
  structuredContent: Record<string, any>;
}>;

function captureRegisteredHandler(registerToolFn: (server: any) => void): RegisteredToolHandler {
  let captured: RegisteredToolHandler | undefined;
  const server = {
    registerTool: (_name: unknown, _meta: unknown, handler: RegisteredToolHandler) => {
      captured = handler;
    },
  };
  registerToolFn(server);
  assert.equal(typeof captured, "function", "expected tool handler to be registered");
  if (!captured) throw new Error("tool handler not captured");
  return captured;
}

test("artifact_management handler rejects legacy flat shape", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "project_context",
    action: "read",
    projectName: "alpha",
  });
  assert.equal(out.structuredContent.resultType, "report");
  assert.equal(out.structuredContent.reasonCode, "artifact_request_invalid");
  assert.equal(out.structuredContent.reasonMeta.failedStep, "input_validation");
});

test("artifact_management handler accepts typed envelope", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "project_context",
    action: "list",
    input: {},
  });
  assert.notEqual(out.structuredContent.reasonCode, "artifact_request_invalid");
});
