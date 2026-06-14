import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  postAppProjectRootAbs,
  postControllerFqcn,
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;
let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

const profileDirAbs = path.join(postAppProjectRootAbs, ".mcp-java-dev-tools");
const profileFileAbs = path.join(profileDirAbs, "request-template.properties");

async function removeTemplateProfile(): Promise<void> {
  await fs.rm(profileDirAbs, { recursive: true, force: true });
}

async function writeTemplateProfile(content: string): Promise<void> {
  await fs.mkdir(profileDirAbs, { recursive: true });
  await fs.writeFile(profileFileAbs, content, "utf8");
}

test.before(async () => {
  runtime = await startPostAppWithAgent();
  mcp = await startMcpClient({
    workspaceRootAbs: socialPlatformRootAbs,
    probeBaseUrl: runtime.probeBaseUrl,
  });
});

test.after(async () => {
  await removeTemplateProfile();
  await mcp?.close();
  await runtime?.stop();
});

test("create_recipe IT: uses built-in fallback template profile when no project fixture profile is provided", async () => {
  if (!mcp) throw new Error("mcp not initialized");
  await removeTemplateProfile();

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: postAppProjectRootAbs,
        classHint: postControllerFqcn,
        methodHint: "updatePost",
        intentMode: "regression",
        authToken: "alice-token",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "recipe");
  const candidate = recipe.structuredContent.requestCandidates[0];
  assert.equal(candidate.method, "PUT");
  assert.equal(candidate.bodyTemplate, "{\"example\":\"value\"}");
  assert.equal(Array.isArray(candidate.assumptions), true);
  assert.equal(Array.isArray(candidate.needsConfirmation), true);
  assert.equal(
    candidate.rationale.some((line: string) => line.includes("(built-in-defaults)")),
    true,
  );
});

test("create_recipe IT: applies project fixture profile overrides for deterministic request templates", async () => {
  if (!mcp) throw new Error("mcp not initialized");
  await writeTemplateProfile([
    "sample.path.param.postId=101",
    "sample.body.default={\"content\":\"fixture-body\"}",
  ].join("\n"));

  const recipe = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "create_recipe",
      input: {
        projectRootAbs: postAppProjectRootAbs,
        classHint: postControllerFqcn,
        methodHint: "updatePost",
        intentMode: "regression",
        authToken: "alice-token",
      },
    },
  })) as any;

  assert.equal(recipe.structuredContent.resultType, "recipe");
  const candidate = recipe.structuredContent.requestCandidates[0];
  assert.equal(candidate.method, "PUT");
  assert.equal(candidate.path, "/api/v1/posts/101");
  assert.equal(candidate.bodyTemplate, "{\"content\":\"fixture-body\"}");
  assert.deepEqual(candidate.needsConfirmation ?? [], []);
  assert.equal(
    candidate.rationale.some((line: string) => line.includes("request-template.properties")),
    true,
  );
});

