const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { PassThrough } = require("node:stream");
const test = require("node:test");

const { runRequestMappingResolverProcess } = require("@/lib/request_mapping_ast_resolver");

function delayedResolverChild(delayMs: number): { child: object; wasKilled: () => boolean } {
  const events = new EventEmitter();
  const stdin = new PassThrough();
  const stdout = new PassThrough();
  const stderr = new PassThrough();
  let killed = false;
  const child = Object.assign(events, {
    stdin,
    stdout,
    stderr,
    kill: () => {
      killed = true;
      return true;
    },
  });
  setTimeout(() => {
    stdout.end(
      JSON.stringify({
        status: "ok",
        requestCandidate: { method: "GET", path: "/types/value/value" },
      }),
    );
    events.emit("close", 0);
  }, delayMs);
  return { child, wasKilled: () => killed };
}

test("[UT][transport][request_mapping_ast_resolver] accepts an eventual resolver result during reconciliation", async () => {
  const fixture = delayedResolverChild(75);
  const result = await runRequestMappingResolverProcess(
    { args: ["-jar", "ignored.jar"], evidence: [] },
    {},
    {
      initialTimeoutMs: 25,
      reconciliationTimeoutMs: 100,
      spawnResolver: () => fixture.child,
    },
  );

  assert.deepEqual(result, {
    status: "ok",
    requestCandidate: { method: "GET", path: "/types/value/value" },
  });
  assert.equal(fixture.wasKilled(), false);
});

test("[UT][transport][request_mapping_ast_resolver] fails closed after reconciliation expires", async () => {
  const fixture = delayedResolverChild(100);
  const result = await runRequestMappingResolverProcess(
    { args: ["-jar", "ignored.jar"], evidence: [] },
    {},
    {
      initialTimeoutMs: 25,
      reconciliationTimeoutMs: 25,
      spawnResolver: () => fixture.child,
    },
  );

  assert.equal(result.status, "report");
  assert.equal(result.reasonCode, "ast_resolver_unavailable");
  assert.equal(fixture.wasKilled(), true);
});
