import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  buildLineKey,
  findLineNumberBySnippet,
  postAppProjectRootAbs,
  postControllerFqcn,
  postControllerSourceFileAbs,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

async function callTool(name: string, args: Record<string, unknown>) {
  if (!mcp) throw new Error("MCP client was not started");
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as any;
}

async function callProbe(action: string, input: Record<string, unknown>) {
  return await callTool("probe", { action, input });
}

async function executeUpdatePost(): Promise<any> {
  if (!runtime) throw new Error("post-app runtime was not started");
  const response = await fetch(`${runtime.apiBaseUrl}/api/v1/posts/101`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer alice-token",
      "x-run-as-tenant": "fixture-tenant",
      "x-run-as-user": "alice",
    },
    body: JSON.stringify({
      content: "Updated from the MCP integration test flow.",
      visibility: "PUBLIC",
      tags: ["fixture", "mcp"],
    }),
  });

  if (response.status !== 200) {
    assert.fail(await response.text());
  }
  return await response.json();
}

async function executeUpdatePostAs(args: { token: string; runAsUser: string; content: string }) {
  if (!runtime) throw new Error("post-app runtime was not started");
  return await fetch(`${runtime.apiBaseUrl}/api/v1/posts/101`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${args.token}`,
      "x-run-as-tenant": "fixture-tenant",
      "x-run-as-user": args.runAsUser,
    },
    body: JSON.stringify({
      content: args.content,
      visibility: "PUBLIC",
      tags: ["fixture", "mcp", "actuate"],
    }),
  });
}

async function executeCreatePostAs(args: { token: string; runAsUser: string; content: string }) {
  if (!runtime) throw new Error("post-app runtime was not started");
  return await fetch(`${runtime.apiBaseUrl}/api/v1/posts`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${args.token}`,
      "x-run-as-tenant": "fixture-tenant",
      "x-run-as-user": args.runAsUser,
    },
    body: JSON.stringify({
      content: args.content,
      visibility: "PUBLIC",
      tags: ["fixture", "mcp", "auth"],
    }),
  });
}

const postServiceFqcn = "com.example.social.post.app.service.PostService";
const postServiceProjectName = "post-service";
const inheritedControllerFqcn = "com.example.social.post.app.controller.AppController";
const inheritedMethodHints = [
  "getData",
  "createData",
  "updateData",
  "deleteData",
  "patchData",
  "requestMappedData",
] as const;
const postServiceSourceAbs = path.join(
  postAppProjectRootAbs,
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "post",
  "app",
  "service",
  "PostService.java",
);

async function resolveFixtureActuationKey(): Promise<string> {
  const fixtureGateLine = await findLineNumberBySnippet(
    postServiceSourceAbs,
    "if (fixtureActuationGate) {",
  );
  return buildLineKey({
    fqcn: postServiceFqcn,
    methodName: "updatePost",
    line: fixtureGateLine,
  });
}

const ACTUATION_TTL_MS = 15_000;
const postServiceProjectsFileAbs = path.join(
  socialPlatformRootAbs,
  ".mcpjvm",
  postServiceProjectName,
  "projects.json",
);

async function writeJson(fileAbs: string, payload: Record<string, unknown>) {
  await fs.mkdir(path.dirname(fileAbs), { recursive: true });
  await fs.writeFile(fileAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

async function armActuation(args: {
  sessionId: string;
  targetKey: string;
  returnBoolean: boolean;
  actuatorId?: string;
}) {
  if (!runtime) throw new Error("post-app runtime was not started");
  return await callProbe("actuate", {
    baseUrl: runtime.probeBaseUrl,
    action: "arm",
    sessionId: args.sessionId,
    targetKey: args.targetKey,
    returnBoolean: args.returnBoolean,
    ttlMs: ACTUATION_TTL_MS,
    actuatorId: args.actuatorId ?? "social-platform-it",
  });
}

async function disarmActuation(sessionId: string) {
  if (!runtime) throw new Error("post-app runtime was not started");
  return await callProbe("actuate", {
    baseUrl: runtime.probeBaseUrl,
    action: "disarm",
    sessionId,
    actuatorId: "social-platform-it",
  });
}

test.before(async () => {
  await writeJson(postServiceProjectsFileAbs, {
    workspaces: [{ projectRoot: postAppProjectRootAbs }],
  });
  runtime = await startPostAppWithAgent();
  mcp = await startMcpClient({
    workspaceRootAbs: socialPlatformRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
  });
});

test.after(async () => {
  await mcp?.close();
  await runtime?.stop();
  await fs.rm(path.join(socialPlatformRootAbs, ".mcpjvm"), { recursive: true, force: true });
});

test("mcp IT: happy-path covers regression, probe status, capture, class inventory, and batch probe operations", async () => {
  if (!runtime) throw new Error("post-app runtime was not started");

  const debug = await callTool("debug_check", {});
  assert.equal(debug.structuredContent.ok, true);

  const project = await callTool("artifact_management", {
    artifactType: "project_context",
    action: "validate",
    input: {
      projectName: postServiceProjectName,
      projectRootAbs: postAppProjectRootAbs,
    },
  });
  assert.equal(project.structuredContent.status, "ok");
  assert.equal(project.structuredContent.hasBuildMarker, true);
  assert.equal(project.structuredContent.hasJavaSourceRoot, true);

  const publicRecipe = await callTool("probe_recipe_create", {
    projectRootAbs: postAppProjectRootAbs,
    classHint: postControllerFqcn,
    methodHint: "listPosts",
    intentMode: "regression",
  });
  assert.equal(publicRecipe.structuredContent.resultType, "recipe");
  assert.equal(publicRecipe.structuredContent.status, "regression_ready");
  assert.equal(publicRecipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(publicRecipe.structuredContent.requestCandidates[0].path, "/api/v1/posts");

  const publicResponse = await fetch(`${runtime.apiBaseUrl}/api/v1/posts?page=0&size=2`);
  assert.equal(publicResponse.status, 200);
  const publicPayload = await publicResponse.json();
  assert.equal(Array.isArray(publicPayload.content), true);
  assert.equal(publicPayload.content.length > 0, true);

  const check = await callProbe("check", {
    baseUrl: runtime.probeBaseUrl,
    timeoutMs: 5_000,
  });
  assert.equal(check.structuredContent.checks.reset.ok, true);
  assert.equal(check.structuredContent.checks.status.ok, true);
  assert.equal(check.structuredContent.checks.status.keyDecodingOk, true);

  const classMethods = await callTool("probe_target_infer", {
    projectRootAbs: postAppProjectRootAbs,
    discoveryMode: "class_methods",
    classHint: postControllerFqcn,
  });
  assert.equal(classMethods.structuredContent.resultType, "class_methods");
  assert.equal(classMethods.structuredContent.status, "ok");
  const updateMethod = classMethods.structuredContent.methods.find(
    (method: any) => method.methodName === "updatePost",
  );
  assert.ok(updateMethod);
  assert.equal(updateMethod.lineSelectionStatus, "validated");
  assert.equal(typeof updateMethod.firstExecutableLine, "number");
  assert.equal(updateMethod.firstExecutableLine > updateMethod.startLine, true);

  const inferred = await callTool("probe_target_infer", {
    projectRootAbs: postAppProjectRootAbs,
    classHint: postControllerFqcn,
    methodHint: "updatePost",
  });
  assert.equal(inferred.structuredContent.status, "ok");
  assert.equal(inferred.structuredContent.resultType, "ranked_candidates");
  assert.equal(inferred.structuredContent.candidates.length, 1);

  const candidate = inferred.structuredContent.candidates[0];
  assert.equal(candidate.lineSelectionStatus, "validated");
  assert.equal(typeof candidate.line, "number");
  const key = buildLineKey({
    fqcn: candidate.fqcn,
    methodName: candidate.methodName,
    line: candidate.line,
  });

  const recipe = await callTool("probe_recipe_create", {
    projectRootAbs: postAppProjectRootAbs,
    classHint: postControllerFqcn,
    methodHint: "updatePost",
    lineHint: candidate.line,
    intentMode: "line_probe",
    authToken: "alice-token",
  });
  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "single_line_probe_ready");
  assert.equal(recipe.structuredContent.synthesizerUsed, "spring");
  assert.equal(recipe.structuredContent.auth.status, "not_required");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "PUT");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v1/posts/1");
  assert.equal(recipe.structuredContent.trigger.path, "/api/v1/posts/1");

  const observe = await disarmActuation("mcp-happy-path");
  assert.equal(observe.structuredContent.response.status, 200);
  assert.equal(observe.structuredContent.response.json.mode, "observe");

  await callProbe("reset", {
    key,
    baseUrl: runtime.probeBaseUrl,
  });

  const resetBatch = await callProbe("reset", {
    keys: [key, `${candidate.fqcn}#${candidate.methodName}`],
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(resetBatch.structuredContent.mode, "probe_batch");
  assert.equal(resetBatch.structuredContent.operation, "reset");
  assert.equal(resetBatch.structuredContent.summary.total, 2);
  assert.equal(resetBatch.structuredContent.summary.failed, 1);

  const updated = await executeUpdatePost();
  assert.equal(updated.id, 101);
  assert.equal(updated.authorUsername, "alice");

  const waited = await callProbe("wait_for_hit", {
    key,
    baseUrl: runtime.probeBaseUrl,
    timeoutMs: 10_000,
    pollIntervalMs: 250,
    maxRetries: 2,
  });
  assert.equal(waited.structuredContent.result.hit, true);
  assert.equal(waited.structuredContent.result.inline, true);

  const statusBatch = await callProbe("status", {
    keys: [key, `${candidate.fqcn}#${candidate.methodName}`],
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(statusBatch.structuredContent.mode, "probe_batch");
  assert.equal(statusBatch.structuredContent.operation, "status");
  assert.equal(statusBatch.structuredContent.summary.total, 2);
  assert.equal(statusBatch.structuredContent.summary.ok, 1);
  assert.equal(statusBatch.structuredContent.summary.failed, 1);

  const status = await callProbe("status", {
    key,
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(status.structuredContent.response.json.lineValidation, "resolvable");
  assert.equal(status.structuredContent.response.json.hitCount >= 1, true);
  assert.equal(status.structuredContent.response.json.capturePreview.available, true);
  const captureId = status.structuredContent.response.json.capturePreview.captureId;
  assert.equal(typeof captureId, "string");

  const capture = await callProbe("capture", {
    captureId,
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(capture.structuredContent.result.found, true);
  assert.equal(capture.structuredContent.result.capture.captureId, captureId);
  assert.equal(capture.structuredContent.result.capture.argsCount >= 1, true);
  assert.equal(capture.structuredContent.result.capture.hasReturnValue, true);
  assert.equal(typeof capture.structuredContent.result.capture.executionStartedAtEpoch, "number");
  assert.equal(typeof capture.structuredContent.result.capture.executionEndedAtEpoch, "number");
  assert.equal(typeof capture.structuredContent.result.capture.executionDurationMs, "number");
  assert.equal(capture.structuredContent.result.capture.executionDurationMs >= 0, true);
  assert.equal(
    capture.structuredContent.result.capture.executionEndedAtEpoch -
      capture.structuredContent.result.capture.executionStartedAtEpoch,
    capture.structuredContent.result.capture.executionDurationMs,
  );
  if (typeof capture.structuredContent.result.capture.threadAllocatedBytesDelta === "number") {
    assert.equal(capture.structuredContent.result.capture.threadAllocatedBytesDelta >= 0, true);
  }

  const resetClass = await callProbe("reset", {
    className: postControllerFqcn,
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(resetClass.structuredContent.mode, "probe_batch");
  assert.equal(resetClass.structuredContent.operation, "reset");
  assert.equal(resetClass.structuredContent.summary.total >= 1, true);
});

test("mcp IT: protected createPost requires bearer auth and executes with run-as headers", async () => {
  if (!runtime) throw new Error("post-app runtime was not started");

  const recipe = await callTool("probe_recipe_create", {
    projectRootAbs: postAppProjectRootAbs,
    classHint: postControllerFqcn,
    methodHint: "createPost",
    intentMode: "regression",
    authToken: "alice-token",
  });
  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "POST");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v1/posts");

  const createdResponse = await executeCreatePostAs({
    token: "alice-token",
    runAsUser: "alice",
    content: "Authenticated createPost request from the MCP integration flow.",
  });
  assert.equal(createdResponse.status, 201);
  const createdPayload = await createdResponse.json();
  assert.equal(createdPayload.authorUsername, "alice");
  assert.equal(createdPayload.content, "Authenticated createPost request from the MCP integration flow.");

  const unauthorizedResponse = await fetch(`${runtime.apiBaseUrl}/api/v1/posts`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-run-as-tenant": "fixture-tenant",
      "x-run-as-user": "alice",
    },
    body: JSON.stringify({
      content: "Missing bearer token must fail closed.",
      visibility: "PUBLIC",
      tags: ["fixture", "mcp", "auth"],
    }),
  });
  assert.equal(unauthorizedResponse.status, 403);
});

test("mcp IT: actuate forces deterministic fixture branch outcomes for the same update request", async () => {
  if (!runtime) throw new Error("post-app runtime was not started");

  const key = await resolveFixtureActuationKey();

  await disarmActuation("mcp-actuate-branch");

  try {
    await armActuation({
      sessionId: "mcp-actuate-branch",
      targetKey: key,
      returnBoolean: false,
    });
    await callProbe("reset", {
      key,
      baseUrl: runtime.probeBaseUrl,
    });

    const deniedResponse = await executeUpdatePostAs({
      token: "alice-token",
      runAsUser: "alice",
      content: "Fixture actuation gate forced fallthrough.",
    });
    assert.equal(deniedResponse.status, 409);
    const deniedText = await deniedResponse.text();
    assert.match(deniedText, /"status"\s*:\s*409/i);

    const deniedWait = await callProbe("wait_for_hit", {
      key,
      baseUrl: runtime.probeBaseUrl,
      timeoutMs: 10_000,
      pollIntervalMs: 250,
      maxRetries: 2,
    });
    assert.equal(deniedWait.structuredContent.result.hit, true);

    await armActuation({
      sessionId: "mcp-actuate-branch",
      targetKey: key,
      returnBoolean: true,
    });
    await callProbe("reset", {
      key,
      baseUrl: runtime.probeBaseUrl,
    });

    const allowedResponse = await executeUpdatePostAs({
      token: "alice-token",
      runAsUser: "alice",
      content: "Fixture actuation gate forced taken-jump.",
    });
    if (allowedResponse.status !== 200) {
      assert.fail(await allowedResponse.text());
    }
    const allowedPayload = await allowedResponse.json();
    assert.equal(allowedPayload.id, 101);
    assert.equal(allowedPayload.authorUsername, "alice");

    const allowedWait = await callProbe("wait_for_hit", {
      key,
      baseUrl: runtime.probeBaseUrl,
      timeoutMs: 10_000,
      pollIntervalMs: 250,
      maxRetries: 2,
    });
    assert.equal(allowedWait.structuredContent.result.hit, true);
    assert.equal(allowedWait.structuredContent.result.inline, true);
  } finally {
    await disarmActuation("mcp-actuate-branch");
  }
});

test("mcp IT: actuate with unresolved strict line target fails closed during reset/wait and returns to observe", async () => {
  if (!runtime) throw new Error("post-app runtime was not started");

  const declarationLine = await findLineNumberBySnippet(
    postControllerSourceFileAbs,
    "public PostDetailResponse updatePost(",
  );
  const invalidLineKey = buildLineKey({
    fqcn: postControllerFqcn,
    methodName: "updatePost",
    line: declarationLine,
  });

  await armActuation({
    sessionId: "mcp-invalid-line",
    targetKey: invalidLineKey,
    returnBoolean: true,
  });

  const invalidReset = await callProbe("reset", {
    key: invalidLineKey,
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(invalidReset.structuredContent.result.reason, "invalid_line_target");

  const invalidWait = await callProbe("wait_for_hit", {
    key: invalidLineKey,
    baseUrl: runtime.probeBaseUrl,
    timeoutMs: 2_000,
    pollIntervalMs: 200,
    maxRetries: 1,
  });
  assert.equal(invalidWait.structuredContent.result.reason, "invalid_line_target");
  assert.equal(invalidWait.structuredContent.result.actionCode, "runtime_not_aligned");

  await disarmActuation("mcp-invalid-line");

  const check = await callProbe("check", {
    baseUrl: runtime.probeBaseUrl,
    timeoutMs: 5_000,
  });
  assert.equal(check.structuredContent.checks.status.json.runtime.mode, "observe");
});

test("mcp IT: arm actuation without returnBoolean fails closed", async () => {
  if (!runtime) throw new Error("post-app runtime was not started");

  const key = await resolveFixtureActuationKey();

  await disarmActuation("mcp-return-bool-required");

  const armed = await callProbe("actuate", {
    baseUrl: runtime.probeBaseUrl,
    action: "arm",
    sessionId: "mcp-return-bool-required",
    targetKey: key,
    ttlMs: ACTUATION_TTL_MS,
    actuatorId: "social-platform-it",
  });
  assert.equal(armed.structuredContent.result.actuated, false);
  assert.equal(armed.structuredContent.result.reasonCode, "return_boolean_required");

  await disarmActuation("mcp-return-bool-required");
});

test("mcp IT: fail-closed paths cover invalid project roots, bad recipe hints, invalid strict lines, and invalid actuation keys", async () => {
  if (!runtime) throw new Error("post-app runtime was not started");

  const invalidProject = await callTool("artifact_management", {
    artifactType: "project_context",
    action: "validate",
    input: {
      projectRootAbs: path.join(postAppProjectRootAbs, "missing-project"),
    },
  });
  assert.equal(invalidProject.structuredContent.status, "project_selector_invalid");
  assert.equal(invalidProject.structuredContent.reason, "projectRootAbs does not exist");

  const invalidRecipe = await callTool("probe_recipe_create", {
    projectRootAbs: postAppProjectRootAbs,
    classHint: "PostController",
    methodHint: "updatePost",
    intentMode: "regression",
  });
  assert.equal(invalidRecipe.structuredContent.status, "class_hint_not_fqcn");
  assert.equal(invalidRecipe.structuredContent.reasonCode, "class_hint_not_fqcn");

  const declarationLine = await findLineNumberBySnippet(
    postControllerSourceFileAbs,
    "public PostDetailResponse updatePost(",
  );
  const invalidLineKey = buildLineKey({
    fqcn: postControllerFqcn,
    methodName: "updatePost",
    line: declarationLine,
  });

  const invalidStatus = await callProbe("status", {
    key: invalidLineKey,
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(invalidStatus.structuredContent.response.json.lineValidation, "invalid_line_target");
  assert.equal(invalidStatus.structuredContent.result.reason, "invalid_line_target");

  const invalidWait = await callProbe("wait_for_hit", {
    key: invalidLineKey,
    baseUrl: runtime.probeBaseUrl,
    timeoutMs: 2_000,
    pollIntervalMs: 200,
    maxRetries: 1,
  });
  assert.equal(invalidWait.structuredContent.result.reason, "invalid_line_target");
  assert.equal(invalidWait.structuredContent.result.actionCode, "runtime_not_aligned");

  const invalidEnable = await callProbe("actuate", {
    baseUrl: runtime.probeBaseUrl,
    action: "arm",
    sessionId: "mcp-invalid-enable",
    targetKey: `${postControllerFqcn}#updatePost`,
    returnBoolean: true,
    ttlMs: ACTUATION_TTL_MS,
  });
  assert.equal(invalidEnable.structuredContent.result.actuated, false);
  assert.equal(invalidEnable.structuredContent.result.reason, "line_key_required");

  const missingCapture = await callProbe("capture", {
    captureId: "missing-capture-id",
    baseUrl: runtime.probeBaseUrl,
  });
  assert.equal(missingCapture.structuredContent.result.found, false);
  assert.equal(missingCapture.structuredContent.result.reason, "capture_not_found");
});

test("mcp IT: inherited controller methods across Spring mappings resolve deterministic request candidates", async () => {
  const expectedByMethod: Record<(typeof inheritedMethodHints)[number], { method: string; path: string }> = {
    getData: { method: "GET", path: "/api/v2/data" },
    createData: { method: "POST", path: "/api/v2/data" },
    updateData: { method: "PUT", path: "/api/v2/data/{id}" },
    deleteData: { method: "DELETE", path: "/api/v2/data/{id}" },
    patchData: { method: "PATCH", path: "/api/v2/data/{id}" },
    requestMappedData: { method: "GET", path: "/api/v2/data/request" },
  };

  for (const methodHint of inheritedMethodHints) {
    const result = await callTool("probe_recipe_create", {
      projectRootAbs: postAppProjectRootAbs,
      classHint: inheritedControllerFqcn,
      methodHint,
      intentMode: "regression",
    });

    assert.equal(result.structuredContent.resultType, "recipe");
    assert.equal(result.structuredContent.status, "regression_ready");
    assert.equal(result.structuredContent.requestCandidates.length > 0, true);
    assert.equal(
      result.structuredContent.requestCandidates[0].method,
      expectedByMethod[methodHint].method,
    );
    assert.equal(
      result.structuredContent.requestCandidates[0].path,
      expectedByMethod[methodHint].path,
    );
  }
});



