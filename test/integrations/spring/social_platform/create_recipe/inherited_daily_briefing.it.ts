import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

const dailyBriefingControllerFqcn =
  "com.example.social.post.app.controller.DailyBriefingController";

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

test("create_recipe IT: inherited Spring mapping supports constants and array-based request mapping", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        classHint: dailyBriefingControllerFqcn,
        methodHint: "summary",
        intentMode: "regression",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(
    recipe.structuredContent.requestCandidates[0].path,
    "/api/v3/briefing/daily/summary",
  );
  assert.equal(
    String(recipe.structuredContent.requestCandidates[0].queryTemplate).includes("page="),
    true,
  );

  const response = await fetch(`${runtime.apiBaseUrl}/api/v3/briefing/daily/summary?page=9`);
  assert.equal(response.status, 200);
  assert.equal(await response.text(), "summary-page-9");
});


