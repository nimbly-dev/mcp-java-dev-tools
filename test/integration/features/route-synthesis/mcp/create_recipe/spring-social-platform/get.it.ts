import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  postControllerFqcn,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/support/spring/social-platform/shared.fixture";

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

test.before(async () => {
  runtime = await startPostAppWithAgent();
  mcp = await startMcpClient({
    workspaceRootAbs: socialPlatformRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
    extraEnv: {
      MCP_JAVA_REQUEST_MAPPING_RESOLVER_JAR: "C:\\stale\\request-mapping-resolver.jar",
      MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH: "C:\\stale\\request-mapping-resolver.jar",
    },
  });
});

test.after(async () => {
  await mcp?.close();
  await runtime?.stop();
});

test("[IT][route_synthesis][create_recipe] listPosts falls back from stale resolver overrides", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        classHint: postControllerFqcn,
        methodHint: "listPosts",
        intentMode: "regression",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v1/posts");

  const response = await fetch(`${runtime.apiBaseUrl}/api/v1/posts?page=0&size=2`);
  assert.equal(response.status, 200);
});
