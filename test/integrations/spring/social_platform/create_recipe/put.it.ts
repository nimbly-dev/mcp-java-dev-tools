import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  postControllerFqcn,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

test.before(async () => {
  runtime = await startPostAppWithAgent();
  mcp = await startMcpClient({
    workspaceRootAbs: socialPlatformRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
  });
});

test.after(async () => {
  await mcp?.close();
  await runtime?.stop();
});

test("create_recipe PUT: updatePost recipe is synthesized and executable", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        classHint: postControllerFqcn,
        methodHint: "updatePost",
        intentMode: "regression",
        authToken: "alice-token",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "PUT");

  const response = await fetch(`${runtime.apiBaseUrl}/api/v1/posts/101`, {
    method: "PUT",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer alice-token",
      "x-run-as-tenant": "fixture-tenant",
      "x-run-as-user": "alice",
    },
    body: JSON.stringify({
      content: "Updated from create_recipe put test.",
      visibility: "PUBLIC",
      tags: ["fixture", "mcp", "put"],
    }),
  });
  assert.equal(response.status, 200);
});

