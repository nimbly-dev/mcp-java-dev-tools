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

test("correlateEvents accepts repeated runtime line hits from one runtime identity", () => {
  const result = correlateEvents(
    [
      {
        eventId: "line-1",
        probeId: "consumer-service",
        runtimeInstanceId: "consumer-1",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1000,
        keyType: "messageId",
        keyValue: "96",
      },
      {
        eventId: "line-2",
        probeId: "consumer-service",
        runtimeInstanceId: "consumer-1",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1100,
        keyType: "messageId",
        keyValue: "96",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5000,
      runtimeEvidenceRequired: true,
      runtimeProbeIds: ["consumer-service"],
    },
  );
  assert.equal(result.status, "ok");
});

test("correlateEvents fails closed on competing runtime identities", () => {
  const result = correlateEvents(
    [
      {
        eventId: "line-1",
        probeId: "consumer-service",
        runtimeInstanceId: "consumer-1",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1000,
        keyType: "messageId",
        keyValue: "96",
      },
      {
        eventId: "line-2",
        probeId: "consumer-service",
        runtimeInstanceId: "consumer-2",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1100,
        keyType: "messageId",
        keyValue: "96",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5000,
      runtimeEvidenceRequired: true,
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "ambiguous_correlation");
});

test("correlateEvents rejects plan-step evidence when runtime evidence is required", () => {
  const result = correlateEvents(
    [
      {
        eventId: "step-1",
        probeId: "producer-service",
        timestampEpochMs: 1000,
        keyType: "messageId",
        keyValue: "96",
        eventType: "http",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5000,
      runtimeEvidenceRequired: true,
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "correlation_key_not_observed");
});

test("correlateEvents rejects runtime evidence without runtime identity", () => {
  const result = correlateEvents(
    [
      {
        eventId: "line-1",
        probeId: "consumer-service",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1000,
        keyType: "messageId",
        keyValue: "96",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5000,
      runtimeEvidenceRequired: true,
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "correlation_runtime_identity_missing");
});

test("correlateEvents does not match a fingerprint with the wrong key type", () => {
  const result = correlateEvents(
    [
      {
        eventId: "line-1",
        probeId: "consumer-service",
        runtimeInstanceId: "consumer-1",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1000,
        keyType: "traceId",
        keyFingerprint: "sha256:7b1a278f5abe8e9da907fc9c29dfd432d60dc76e17b0fabab659d2a508bc65c4",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5000,
      runtimeEvidenceRequired: true,
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "correlation_key_not_observed");
});

test("correlateEvents enforces configured runtime instance scope", () => {
  const result = correlateEvents(
    [
      {
        eventId: "line-1",
        probeId: "consumer-service",
        runtimeInstanceId: "consumer-1",
        eventType: "runtime_line_hit",
        timestampEpochMs: 1000,
        keyType: "messageId",
        keyValue: "96",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5000,
      runtimeEvidenceRequired: true,
      runtimeInstanceIds: ["consumer-2"],
    },
  );
  assert.equal(result.status, "fail_closed");
  assert.equal(result.reasonCode, "correlation_probe_scope_mismatch");
});

test("correlateEvents does not let fingerprint matching bypass line or execution scope", () => {
  const result = correlateEvents(
    [
      {
        eventId: "wrong-line",
        probeId: "probe-a",
        timestampEpochMs: 1_000,
        eventType: "runtime_line_hit",
        lineKey: "other.Class#run:9",
        keyType: "messageId",
        keyFingerprint: "sha256:7b1a278f5abe8e9da907fc9c29dfd432d60dc76e17b0fabab659d2a508bc65c4",
        runtimeInstanceId: "instance-a",
        correlationExecutionId: "execution-old",
      },
    ],
    {
      keyType: "messageId",
      keyValue: "96",
      maxWindowMs: 5_000,
      runtimeEvidenceRequired: true,
      runtimeLineKeys: ["expected.Class#run:10"],
      runtimeExecutionId: "execution-current",
    },
  );
  assert.equal(result.status, "fail_closed");
});
