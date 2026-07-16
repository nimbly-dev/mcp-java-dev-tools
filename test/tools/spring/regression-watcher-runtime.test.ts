const assert = require("node:assert/strict");
const test = require("node:test");

const { executeWatchers } = require("../../../tools/features/regression-suite/shared/regression_watcher_runtime");

function watcherArgs(expectation: Record<string, unknown>, retryMax: number, nowMs: () => number) {
  return {
    contract: {
      watchers: [
        {
          id: "indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: { request: { method: "GET", url: "http://127.0.0.1/index" } },
          },
          waitPolicy: { timeoutMs: 100_000, retryMax },
          expect: [expectation],
        },
      ],
    },
    resolvedContext: {},
    registry: new Map([
      [
        "http",
        {
          protocol: "http",
          execute: async () => ({
            status: "pass",
            protocol: "http",
            statusCode: 200,
            durationMs: 1,
            bodyText: "{}",
            bodyPreview: "{}",
          }),
        },
      ],
    ]),
    stepRows: [{ order: 1, id: "trigger", status: "pass" }],
    stepContextsByOrder: new Map(),
    nowMs,
    sleepMs: async () => undefined,
  };
}

test("executeWatchers reports required missing paths after all inclusive attempts", async () => {
  const out = await executeWatchers(
    watcherArgs(
      {
        id: "required_state",
        actualPath: "response.bodyJson.state",
        operator: "field_equals",
        expected: "ready",
      },
      4,
      () => 1_000,
    ) as never,
  );

  assert.equal(out.watcherRows[0]?.status, "blocked_runtime");
  assert.equal(out.watcherRows[0]?.outcome, "blocked");
  assert.equal(out.watcherRows[0]?.reasonCode, "watcher_actual_path_missing_retry_exhausted");
  assert.equal(out.watcherRows[0]?.attemptCount, 4);
});

test("executeWatchers reports watcher_timeout when its deadline expires before an attempt", async () => {
  let clockReads = 0;
  const out = await executeWatchers(
    watcherArgs(
      {
        id: "required_state",
        actualPath: "response.bodyJson.state",
        operator: "field_equals",
        expected: "ready",
      },
      4,
      () => (clockReads++ === 0 ? 2_000 : 200_000),
    ) as never,
  );

  assert.equal(out.watcherRows[0]?.status, "blocked_runtime");
  assert.equal(out.watcherRows[0]?.outcome, "timed_out");
  assert.equal(out.watcherRows[0]?.reasonCode, "watcher_timeout");
  assert.equal(out.watcherRows[0]?.attemptCount, 0);
});

test("executeWatchers keeps an optional missing path non-terminal", async () => {
  const out = await executeWatchers(
    watcherArgs(
      {
        id: "optional_state",
        actualPath: "response.bodyJson.state",
        operator: "field_equals",
        expected: "ready",
        required: false,
      },
      4,
      () => 1_000,
    ) as never,
  );

  assert.equal(out.watcherRows[0]?.status, "pass");
  assert.equal(out.watcherRows[0]?.outcome, "verified");
  assert.equal(out.watcherRows[0]?.attemptCount, 1);
  assert.equal(out.watcherRows[0]?.assertions?.[0]?.status, "skipped_optional");
  assert.equal(out.watcherRows[0]?.assertions?.[0]?.reasonCode, "optional_actual_path_missing");
});

test("executeWatchers renews the active suite lease before each poll", async () => {
  let renewals = 0;
  const args = watcherArgs(
    {
      id: "required_state",
      actualPath: "response.bodyJson.state",
      operator: "field_equals",
      expected: "ready",
    },
    3,
    () => 1_000,
  ) as any;
  args.renewSuiteLease = async () => {
    renewals += 1;
  };
  const out = await executeWatchers(args);

  assert.equal(out.watcherRows[0]?.attemptCount, 3);
  assert.equal(renewals >= 3, true);
  assert.notEqual(out.watcherRows[0]?.reasonCode, "watcher_attempt_non_monotonic");
});
