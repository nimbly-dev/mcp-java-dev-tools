import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  socialPlatformRootAbs,
  startMcpClient,
  startPostAppWithAgent,
} from "@test/support/spring/social-platform/shared.fixture";

const tagLifecycleControllerFqcn = "com.example.social.post.app.controller.TagLifecycleController";
const creatorDigestControllerFqcn =
  "com.example.social.post.app.controller.CreatorDigestController";

type DiscoveredHandler = {
  httpMethod: string;
  path: string;
  methodName: string;
  strictLineKey: string;
  lineSelectionStatus: string;
  lineSelectionSource: string;
};

type HandlerInventory = {
  resultType: string;
  status: string;
  controllerFqcn: string;
  framework: string;
  handlers: DiscoveredHandler[];
};

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

test("[IT][route_synthesis][discover_handlers] discovers Spring handlers with validated Strict Line Keys", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const result = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "discover_handlers",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        classHint: tagLifecycleControllerFqcn,
        probeBaseUrl: runtime.probeBaseUrl,
      },
    },
  })) as unknown as { structuredContent: HandlerInventory };

  const inventory = result.structuredContent;
  assert.equal(
    inventory.resultType,
    "handler_inventory",
    `unexpected discovery response: ${JSON.stringify(inventory)}`,
  );
  assert.equal(inventory.status, "ready");
  assert.equal(inventory.controllerFqcn, tagLifecycleControllerFqcn);
  assert.equal(inventory.framework, "spring_http");
  assert.equal(Array.isArray(inventory.handlers), true);

  const createTag = inventory.handlers.find((handler) => handler.methodName === "createTag");
  assert.ok(createTag, "expected createTag handler in inventory");
  assert.equal(createTag.httpMethod, "POST");
  assert.equal(createTag.path, "/api/v2/tenant/value/tags");
  assert.match(createTag.strictLineKey, /TagLifecycleController#createTag:\d+$/);
  assert.equal(createTag.lineSelectionStatus, "validated");
  assert.equal(createTag.lineSelectionSource, "runtime_probe_validation");
  assert.equal(
    inventory.handlers.some((handler) => handler.methodName === "tagLifecycleService"),
    false,
  );
});

test("[IT][route_synthesis][discover_handlers] includes handlers inherited through abstract and interface mappings", async () => {
  if (!runtime || !mcp) throw new Error("runtime/mcp not initialized");

  const result = (await mcp.client.callTool({
    name: "route_synthesis",
    arguments: {
      action: "discover_handlers",
      input: {
        projectRootAbs: path.join(socialPlatformRootAbs, "post-service", "post-app"),
        classHint: creatorDigestControllerFqcn,
        additionalSourceRoots: ["post-service/post-api"],
        probeBaseUrl: runtime.probeBaseUrl,
      },
    },
  })) as unknown as { structuredContent: HandlerInventory };

  assert.equal(result.structuredContent.resultType, "handler_inventory");
  const digest = result.structuredContent.handlers.find(
    (handler) => handler.methodName === "digest",
  );
  assert.ok(digest, "expected inherited digest handler in inventory");
  assert.equal(digest.httpMethod, "GET");
  assert.equal(digest.path, "/api/v4/creator/digest");
  assert.match(digest.strictLineKey, /AbstractCreatorDigestController#digest:\d+$/);
});
