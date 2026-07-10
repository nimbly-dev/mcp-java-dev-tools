const assert = require("node:assert/strict");
const test = require("node:test");

const { correlateEvents } = require("@tools-feature-regression-suite");

test("correlateEvents returns deterministic ordered timeline when correlation is valid", () => {
  const result = correlateEvents(
    [
      {
        eventId: "e2",
        probeId: "user-service",
        timestampEpochMs: 2000,
        keyType: "traceId",
        keyValue: "abc-123",
      },
      {
        eventId: "e1",
        probeId: "gateway-service",
        timestampEpochMs: 1000,
        keyType: "traceId",
        keyValue: "abc-123",
      },
    ],
    {
      keyType: "traceId",
      keyValue: "abc-123",
      maxWindowMs: 5000,
      expectedFlow: ["gateway-service", "user-service"],
    },
  );
  assert.equal(result.status, "ok");
  assert.equal(result.timeline[0].eventId, "e1");
  assert.equal(result.timeline[1].eventId, "e2");
});

test("correlateEvents fails closed on ambiguous duplicate probe matches", () => {
  const result = correlateEvents(
    [
      {
        eventId: "e1",
        probeId: "gateway-service",
        timestampEpochMs: 1000,
        keyType: "traceId",
        keyValue: "abc-123",
      },
      {
        eventId: "e2",
        probeId: "gateway-service",
        timestampEpochMs: 1200,
        keyType: "traceId",
        keyValue: "abc-123",
      },
    ],
    {
      keyType: "traceId",
      keyValue: "abc-123",
      maxWindowMs: 5000,
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "ambiguous_correlation");
});

test("correlateEvents fails closed when expected flow ordering is violated", () => {
  const result = correlateEvents(
    [
      {
        eventId: "e1",
        probeId: "user-service",
        timestampEpochMs: 1000,
        keyType: "traceId",
        keyValue: "abc-123",
      },
      {
        eventId: "e2",
        probeId: "gateway-service",
        timestampEpochMs: 2000,
        keyType: "traceId",
        keyValue: "abc-123",
      },
    ],
    {
      keyType: "traceId",
      keyValue: "abc-123",
      maxWindowMs: 5000,
      expectedFlow: ["gateway-service", "user-service"],
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "flow_expectation_mismatch");
});

