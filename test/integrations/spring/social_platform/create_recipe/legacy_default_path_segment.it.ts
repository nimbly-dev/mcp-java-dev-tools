import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

const featureFlagsControllerFqcn =
  "com.example.social.post.app.controller.FeatureFlagsController";

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

test("create_recipe IT: route synthesis avoids legacy-default segment when concrete controller mapping is explicit", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        classHint: featureFlagsControllerFqcn,
        methodHint: "featureFlags",
        intentMode: "regression",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v9/feature-flags");
  assert.ok(
    !String(recipe.structuredContent.requestCandidates[0].path).includes("/legacy-default/"),
    "candidate path must not include /legacy-default segment",
  );

  const response = await fetch(`${runtime.apiBaseUrl}/api/v9/feature-flags`);
  assert.equal(response.status, 200);
  assert.equal(await response.text(), "feature-flags-ok");
});


