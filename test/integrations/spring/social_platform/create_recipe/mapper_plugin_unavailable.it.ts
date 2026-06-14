import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  postControllerFqcn,
  resolveCoreEntrypointMapperJar,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

test.before(async () => {
  runtime = await startPostAppWithAgent();
  const coreMapperJarAbs = await resolveCoreEntrypointMapperJar();
  mcp = await startMcpClient({
    workspaceRootAbs: socialPlatformRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
    extraEnv: {
      MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH: coreMapperJarAbs,
    },
  });
});

test.after(async () => {
  await mcp?.close();
  await runtime?.stop();
});

test("create_recipe REPORT: preserves mapper_plugin_unavailable from AST resolver", async () => {
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

  assert.equal(recipe.structuredContent.resultType, "report");
  assert.equal(recipe.structuredContent.status, "api_request_not_inferred");
  assert.equal(recipe.structuredContent.reasonCode, "mapper_plugin_unavailable");
  assert.equal(recipe.structuredContent.failedStep, "extractor_plugin_discovery");
  assert.equal(
    Array.isArray(recipe.structuredContent.requestCandidates) &&
      recipe.structuredContent.requestCandidates.length === 0,
    true,
  );
  assert.deepEqual(recipe.structuredContent.attemptedStrategies, [
    "java_ast_index_lookup",
    "service_loader_plugin_discovery",
  ]);
});

