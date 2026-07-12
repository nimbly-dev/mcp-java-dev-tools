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

test("artifact_management handler accepts probe_config reload envelope", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "probe_config",
    action: "reload",
    input: {},
  });
  assert.notEqual(out.structuredContent.reasonCode, "artifact_request_invalid");
});

test("artifact_management handler accepts snake_case aliases inside typed input envelope", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "execution_export",
    action: "generate",
    input: {
      project_name: "test-project-performance",
      execution_profile: "test-performance-stress-suite",
      mode: "sh",
    },
  });
  assert.notEqual(out.structuredContent.reasonCode, "artifact_request_invalid");
  assert.notEqual(out.structuredContent.reasonCode, "project_artifact_ambiguous");
});

test("artifact_management handler accepts project_root_abs alias inside typed input envelope", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "project_context",
    action: "validate",
    input: {
      project_root_abs: process.cwd(),
    },
  });
  assert.notEqual(out.structuredContent.reasonCode, "artifact_request_invalid");
});

test("artifact_management handler rejects regression_plan windowed read when pagination params are missing", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "regression_plan",
    action: "read",
    input: {
      projectName: "test-project-performance",
      planName: "mcp-tool-performance-stress-5000",
      query: {
        select: ["steps"],
      },
    },
  });
  assert.equal(out.structuredContent.resultType, "report");
  assert.equal(out.structuredContent.reasonCode, "artifact_request_invalid");
  assert.equal(out.structuredContent.reasonMeta.failedStep, "input_validation");
});

test("artifact_management handler rejects run_result watcher query when pagination params are missing", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  const out = await handler({
    artifactType: "run_result",
    action: "read",
    input: {
      projectName: "test-project-watchers",
      planName: "watcher-plan",
      runId: "06-05-2026-07-27-58AM",
      query: {
        select: ["watchers"],
      },
    },
  });
  assert.equal(out.structuredContent.resultType, "report");
  assert.equal(out.structuredContent.reasonCode, "artifact_request_invalid");
  assert.equal(out.structuredContent.reasonMeta.failedStep, "input_validation");
});

test("artifact_management handler requires correlation_state for run_result backfill", async () => {
  const handler = captureRegisteredHandler((server: any) =>
    registerArtifactManagementTool(server, { workspaceRootAbs: process.cwd() }),
  );
  for (const stateSurface of [undefined, "run_state"]) {
    const out = await handler({
      artifactType: "run_result",
      action: "backfill",
      input: {
        projectName: "test-project",
        ...(stateSurface ? { stateSurface } : {}),
      },
    });
    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "artifact_request_invalid");
  }
});
