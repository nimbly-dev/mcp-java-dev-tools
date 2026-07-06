const assert = require("node:assert/strict");
const test = require("node:test");

const {
  renderWatcherResults,
} = require("@tools-regression-execution-plan-spec/regression_watcher_results_report.util");

test("renderWatcherResults returns summary and detail rows for watcher outcomes", () => {
  const rendered = renderWatcherResults({
    executionResult: {
      status: "fail",
      triggerStatus: "pass",
      watcherStatus: "fail",
      watchers: [
        {
          id: "search-index",
          dependencyStepOrder: 2,
          status: "pass",
          outcome: "verified",
          attemptCount: 3,
          durationMs: 2100,
          reasonCode: "watcher_verified",
          waitPolicy: {
            timeoutMs: 10000,
            retryMax: 5,
          },
        },
        {
          id: "profile-read-model",
          dependencyStepOrder: 3,
          status: "fail_assertion",
          outcome: "failed_expectation",
          attemptCount: 2,
          durationMs: 4000,
          reasonCode: "watcher_expectation_failed",
          waitPolicy: {
            timeoutMs: 12000,
            retryMax: 4,
          },
        },
      ],
    },
  });

  assert.ok(rendered);
  assert.equal(rendered.summary.triggerStatus, "pass");
  assert.equal(rendered.summary.watcherStatus, "fail");
  assert.equal(rendered.summary.watcherCount, 2);
  assert.equal(rendered.summary.verifiedCount, 1);
  assert.equal(rendered.summary.failedExpectationCount, 1);
  assert.equal(rendered.summary.timedOutCount, 0);
  assert.equal(rendered.summary.blockedCount, 0);
  assert.equal(rendered.rows.length, 2);
  assert.equal(rendered.rows[0].id, "search-index");
  assert.equal(rendered.rows[0].timeoutMs, "10000");
  assert.equal(rendered.rows[1].reasonCode, "watcher_expectation_failed");
  assert.match(rendered.table, /\| Watcher ID \| Dependency Step \| Status \| Outcome \| Attempts \| Duration \(ms\) \| Timeout \(ms\) \| Retry Max \| Reason Code \|/);
});

test("renderWatcherResults counts timed out and blocked watcher outcomes deterministically", () => {
  const rendered = renderWatcherResults({
    executionResult: {
      status: "blocked",
      triggerStatus: "pass",
      watcherStatus: "blocked",
      watchers: [
        {
          id: "index-poller",
          dependencyStepOrder: 1,
          status: "blocked_runtime",
          outcome: "timed_out",
          attemptCount: 4,
          durationMs: 8000,
          reasonCode: "watcher_timeout",
          waitPolicy: {
            timeoutMs: 8000,
            retryMax: 4,
          },
        },
        {
          id: "audit-store",
          dependencyStepOrder: 1,
          status: "blocked_runtime",
          outcome: "blocked",
          attemptCount: 1,
          durationMs: 50,
          reasonCode: "watcher_target_unreachable",
        },
      ],
    },
  });

  assert.ok(rendered);
  assert.equal(rendered.summary.triggerStatus, "pass");
  assert.equal(rendered.summary.watcherStatus, "blocked");
  assert.equal(rendered.summary.watcherCount, 2);
  assert.equal(rendered.summary.verifiedCount, 0);
  assert.equal(rendered.summary.failedExpectationCount, 0);
  assert.equal(rendered.summary.timedOutCount, 1);
  assert.equal(rendered.summary.blockedCount, 1);
  assert.equal(rendered.rows[0].id, "audit-store");
  assert.equal(rendered.rows[0].retryMax, "n/a");
  assert.equal(rendered.rows[1].id, "index-poller");
  assert.equal(rendered.rows[1].reasonCode, "watcher_timeout");
});

test("renderWatcherResults returns undefined when no watchers were executed", () => {
  const rendered = renderWatcherResults({
    executionResult: {
      status: "pass",
      triggerStatus: "pass",
      watcherStatus: "not_configured",
      steps: [],
    },
  });

  assert.equal(rendered, undefined);
});

test("renderWatcherResults derives watcherStatus from watcher rows when omitted", () => {
  const rendered = renderWatcherResults({
    executionResult: {
      status: "blocked",
      triggerStatus: "pass",
      watchers: [
        {
          id: "feed-cache",
          dependencyStepOrder: 1,
          status: "blocked_runtime",
          outcome: "timed_out",
          attemptCount: 4,
          durationMs: 5000,
          reasonCode: "watcher_timeout",
        },
      ],
    },
  });

  assert.ok(rendered);
  assert.equal(rendered.summary.watcherStatus, "blocked");
});

test("renderWatcherResults derives triggerStatus from step rows when omitted", () => {
  const rendered = renderWatcherResults({
    executionResult: {
      status: "blocked",
      watchers: [
        {
          id: "search-index",
          dependencyStepOrder: 1,
          status: "blocked_runtime",
          outcome: "timed_out",
          attemptCount: 4,
          durationMs: 5000,
          reasonCode: "watcher_timeout",
        },
      ],
      steps: [
        {
          order: 1,
          id: "trigger_index",
          status: "pass",
        },
      ],
    },
  });

  assert.ok(rendered);
  assert.equal(rendered.summary.triggerStatus, "pass");
});
