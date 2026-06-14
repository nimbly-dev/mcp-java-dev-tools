import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  postAppProjectRootAbs,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

const creatorPulseControllerFqcn =
  "com.example.social.post.app.controller.CreatorPulseController";
const creatorDigestControllerFqcn =
  "com.example.social.post.app.controller.CreatorDigestController";
const bytecodeFallbackStrategy = "java_bytecode_spring_annotation_fallback";
const bytecodeMappingEvidence = "mapping_source=bytecode_annotation";
const postApiClassesRootAbs = path.join(
  socialPlatformRootAbs,
  "post-service",
  "post-api",
  "target",
  "classes",
);

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
let bytecodeOnlyRootAbs: string | undefined;

test.before(async () => {
  bytecodeOnlyRootAbs = await fs.mkdtemp(path.join(os.tmpdir(), "mcpjvm-bytecode-only-"));
  const mirroredTargetClassesAbs = path.join(bytecodeOnlyRootAbs, "target", "classes");
  await fs.mkdir(mirroredTargetClassesAbs, { recursive: true });
  await fs.cp(postApiClassesRootAbs, mirroredTargetClassesAbs, { recursive: true });

  runtime = await startPostAppWithAgent();
  mcp = await startMcpClient({
    workspaceRootAbs: postAppProjectRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
  });
});

test.after(async () => {
  await mcp?.close();
  await runtime?.stop();
  if (bytecodeOnlyRootAbs) {
    await fs.rm(bytecodeOnlyRootAbs, { recursive: true, force: true });
  }
});

async function createRecipe(args: { classHint: string; methodHint: string }) {
  if (!mcp) throw new Error("mcp not initialized");
  return (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        additionalSourceRoots: bytecodeOnlyRootAbs ? [bytecodeOnlyRootAbs] : [],
        classHint: args.classHint,
        methodHint: args.methodHint,
        intentMode: "regression",
      },
    },
  })) as any;
}

test("create_recipe IT: bytecode annotation fallback resolves interface mapping without source bodies", async () => {
  const recipe = await createRecipe({
    classHint: creatorPulseControllerFqcn,
    methodHint: "pulse",
  });

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v3/creator/pulse");

  assert.equal(recipe.structuredContent.attemptedStrategies.includes(bytecodeFallbackStrategy), true);
  assert.equal(recipe.structuredContent.evidence.includes(bytecodeMappingEvidence), true);
});

test("create_recipe IT: bytecode annotation fallback resolves interface->abstract->concrete chain", async () => {
  const recipe = await createRecipe({
    classHint: creatorDigestControllerFqcn,
    methodHint: "digest",
  });

  assert.equal(recipe.structuredContent.resultType, "recipe");
  assert.equal(recipe.structuredContent.status, "regression_ready");
  assert.equal(recipe.structuredContent.requestCandidates[0].method, "GET");
  assert.equal(recipe.structuredContent.requestCandidates[0].path, "/api/v4/creator/digest");

  assert.equal(recipe.structuredContent.attemptedStrategies.includes(bytecodeFallbackStrategy), true);
  assert.equal(recipe.structuredContent.evidence.includes(bytecodeMappingEvidence), true);
});


