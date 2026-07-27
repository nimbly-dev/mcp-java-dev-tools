import assert from "node:assert/strict";
import test from "node:test";

import { startPostAppWithAgent } from "@test/support/spring/social-platform/shared.fixture";

let runtime: Awaited<ReturnType<typeof startPostAppWithAgent>> | undefined;

test.before(async () => {
  runtime = await startPostAppWithAgent();
});

test.after(async () => {
  await runtime?.stop();
});

test("[IT][transport-execution][delete_tag] delete returns 500 when downstream tags endpoint rejects DELETE", async () => {
  if (!runtime) throw new Error("runtime not initialized");

  const tenantId = "tenant-154";
  const tagName = "safety-tag";
  const base = `${runtime.apiBaseUrl}/api/v2/tenant/${tenantId}/tags`;

  const createResponse = await fetch(base, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: tagName,
      color: "#0ea5e9",
    }),
  });
  assert.equal(createResponse.status, 200);

  const lockResponse = await fetch(`${base}/${tagName}/lock`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      actorId: "fixture-user",
      actorRole: "ADMIN",
    }),
  });
  assert.equal(lockResponse.status, 200);

  const deleteResponse = await fetch(`${base}/${tagName}`, {
    method: "DELETE",
  });
  assert.equal(deleteResponse.status, 500);

  const body = (await deleteResponse.json()) as {
    type?: string;
    message?: string;
    downstreamStatus?: number;
  };
  assert.equal(body.type, "SERVER_ERROR");
  assert.equal(body.downstreamStatus, 405);
  assert.match(
    String(body.message),
    /DELETE \/internal-tags-api\/tags returned 405 Method Not Allowed/,
  );
});
