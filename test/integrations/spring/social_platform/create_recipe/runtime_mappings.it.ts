import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  postAppProjectRootAbs,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

const postControllerFqcn = "com.example.social.post.app.controller.PostController";

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

async function createRecipe(args: {
  discoveryPreference: "static_only" | "runtime_first" | "runtime_only";
  mappingsBaseUrl?: string;
  classHint: string;
  methodHint: string;
}) {
  if (!mcp) throw new Error("mcp not initialized");
  return (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: postAppProjectRootAbs,
        classHint: args.classHint,
        methodHint: args.methodHint,
        intentMode: "regression",
        discoveryPreference: args.discoveryPreference,
        ...(args.mappingsBaseUrl ? { mappingsBaseUrl: args.mappingsBaseUrl } : {}),
      },
    },
  })) as any;
}

test("create_recipe IT: runtime_first resolves request candidate from actuator mappings", async () => {
  if (!runtime) throw new Error("runtime not initialized");

  const recipe = await createRecipe({
    discoveryPreference: "runtime_first",
    mappingsBaseUrl: `${runtime.apiBaseUrl}/actuator`,
    classHint: postControllerFqcn,
    methodHint: "listPosts",
  });

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v1/posts");
  assert.equal(recipe.structuredContent.evidence.includes("mapping_source=runtime_actuator"), true);
  assert.equal(
    recipe.structuredContent.evidence.some((entry: string) =>
      entry.startsWith("runtime_mappings_fallback_reason="),
    ),
    false,
  );
});

test("create_recipe IT: runtime_first falls back to static synthesis when runtime mappings endpoint is unreachable", async () => {
  const recipe = await createRecipe({
    discoveryPreference: "runtime_first",
    mappingsBaseUrl: "http://127.0.0.1:1/actuator/mappings",
    classHint: postControllerFqcn,
    methodHint: "listPosts",
  });

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v1/posts");
  assert.equal(
    recipe.structuredContent.evidence.includes(
      "runtime_mappings_fallback_reason=runtime_mappings_unreachable",
    ),
    true,
  );
});

test("create_recipe IT: runtime_only fails closed when runtime mappings endpoint is unreachable", async () => {
  const recipe = await createRecipe({
    discoveryPreference: "runtime_only",
    mappingsBaseUrl: "http://127.0.0.1:1/actuator/mappings",
    classHint: postControllerFqcn,
    methodHint: "listPosts",
  });

  assert.equal(recipe.structuredContent.resultType, "report");
  assert.equal(recipe.structuredContent.reasonCode, "runtime_mappings_unreachable");
  assert.equal(recipe.structuredContent.nextActionCode, "verify_runtime_mappings_endpoint");
  assert.equal(recipe.structuredContent.failedStep, "runtime_mapping_fetch");
});


