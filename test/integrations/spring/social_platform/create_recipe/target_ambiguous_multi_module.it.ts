import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

const postControllerFqcn = "com.example.social.post.app.controller.PostController";
const duplicateControllerSourceFileAbs = path.join(
  socialPlatformRootAbs,
  "user-service",
  "user-app",
  "src",
  "main",
  "java",
  "com",
  "example",
  "social",
  "post",
  "app",
  "controller",
  "PostController.java",
);

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

async function createDuplicatePostController(): Promise<void> {
  await fs.mkdir(path.dirname(duplicateControllerSourceFileAbs), { recursive: true });
  await fs.writeFile(
    duplicateControllerSourceFileAbs,
    [
      "package com.example.social.post.app.controller;",
      "",
      "public class PostController {",
      "  public String listPosts() {",
      '    return "shadow-module";',
      "  }",
      "}",
      "",
    ].join("\n"),
    "utf8",
  );
}

async function removeDuplicatePostController(): Promise<void> {
  await fs.rm(duplicateControllerSourceFileAbs, { force: true });
}

test.before(async () => {
  await createDuplicatePostController();
  runtime = await startPostAppWithAgent();
  mcp = await startMcpClient({
    workspaceRootAbs: socialPlatformRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
  });
});

test.after(async () => {
  await mcp?.close();
  await runtime?.stop();
  await removeDuplicatePostController();
});

test("create_recipe REPORT: multi-module duplicate target fails closed as target_ambiguous", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: socialPlatformRootAbs,
        classHint: postControllerFqcn,
        methodHint: "listPosts",
        intentMode: "regression",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "report");
  assert.equal(recipe.structuredContent.status, "target_not_inferred");
  assert.equal(recipe.structuredContent.reasonCode, "target_ambiguous");
  assert.equal(recipe.structuredContent.failedStep, "target_selection");
  assert.equal(
    Array.isArray(recipe.structuredContent.requestCandidates) &&
      recipe.structuredContent.requestCandidates.length === 0,
    true,
  );
});

