const assert = require("node:assert/strict");
const test = require("node:test");

const {
  normalizeWatcherProviderResult,
  resolveWatcherProviderExecution,
  summarizeWatcherObservation,
} = require("@tools-regression-execution-plan-spec/regression_watcher_provider.util");

test("resolveWatcherProviderExecution supports transport-neutral watcher contract with http as first concrete provider", () => {
  const resolved = resolveWatcherProviderExecution({
    watcher: {
      id: "indexed_ready",
      dependency: { stepOrder: 1 },
      provider: {
        type: "http",
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/search/${eventId}",
            body: { status: "ready" },
          },
        },
        config: {
          response: {
            bodyFormat: "json",
          },
        },
      },
      expect: [],
    },
    context: {
      apiBaseUrl: "http://localhost:8082",
      eventId: "evt-100",
      "runtime.requestTimeoutMs": 30_000,
    },
    timeoutMs: 20_000,
  });

  assert.equal(resolved.ok, true);
  if (!resolved.ok) {
    throw new Error("expected resolved watcher execution");
  }
  assert.equal(resolved.execution.providerType, "http");
  assert.equal(resolved.execution.protocol, "http");
  assert.equal(resolved.execution.responseBodyFormat, "json");
  assert.equal(resolved.execution.payload.url, "http://localhost:8082/search/evt-100");
  assert.equal(resolved.execution.payload.timeoutMs, 20_000);
  assert.equal(resolved.execution.payload.body, JSON.stringify({ status: "ready" }));
  assert.deepEqual(resolved.execution.payload.headers, { "Content-Type": "application/json" });
});

test("resolveWatcherProviderExecution fails closed for unsupported watcher providers", () => {
  const resolved = resolveWatcherProviderExecution({
    watcher: {
      id: "indexed_ready",
      dependency: { stepOrder: 1 },
      provider: {
        type: "postgres",
        config: {
          response: {
            bodyFormat: "json",
          },
        },
      },
      expect: [],
    },
    context: {},
  });

  assert.equal(resolved.ok, false);
  if (resolved.ok) {
    throw new Error("expected unsupported provider to fail closed");
  }
  assert.equal(resolved.reasonCode, "watcher_provider_not_supported");
  assert.equal(resolved.reasonMeta?.providerType, "postgres");
});

test("normalizeWatcherProviderResult fails closed when http watcher requires json normalization and body is invalid", () => {
  const normalized = normalizeWatcherProviderResult({
    execution: {
      providerType: "http",
      protocol: "http",
      payload: { method: "GET", url: "http://localhost:8082/search/evt-100" },
      responseBodyFormat: "json",
    },
    transport: {
      status: "pass",
      protocol: "http",
      statusCode: 200,
      durationMs: 12,
      bodyText: "not-json",
    },
  });

  assert.equal(normalized.ok, false);
  if (normalized.ok) {
    throw new Error("expected normalization failure");
  }
  assert.equal(normalized.reasonCode, "watcher_response_normalization_failed");
  assert.equal(normalized.reasonMeta?.cause, "response_body_json_invalid");
});

test("normalizeWatcherProviderResult emits deterministic assertion-friendly envelope", () => {
  const normalized = normalizeWatcherProviderResult({
    execution: {
      providerType: "http",
      protocol: "http",
      payload: { method: "GET", url: "http://localhost:8082/search/evt-100" },
      responseBodyFormat: "auto",
    },
    transport: {
      status: "pass",
      protocol: "http",
      statusCode: 200,
      durationMs: 15,
      bodyText: "{\"state\":\"ready\"}",
      headers: { "content-type": "application/json" },
    },
  });

  assert.equal(normalized.ok, true);
  if (!normalized.ok) {
    throw new Error("expected normalization success");
  }
  assert.equal(normalized.envelope.status, "pass");
  assert.equal(normalized.envelope.provider.type, "http");
  assert.equal(normalized.envelope.response.statusCode, 200);
  assert.equal(normalized.envelope.response.bodyFormat, "json");
  assert.deepEqual(normalized.envelope.response.bodyJson, { state: "ready" });
  assert.equal(normalized.envelope.transport.reasonCode, null);
});

test("resolveWatcherProviderExecution accepts empty response config and defaults bodyFormat to auto", () => {
  const resolved = resolveWatcherProviderExecution({
    watcher: {
      id: "indexed_ready",
      dependency: { stepOrder: 1 },
      provider: {
        type: "http",
        transport: {
          http: {
            method: "GET",
            url: "http://localhost:8082/search/evt-100",
          },
        },
        config: {
          response: {},
        },
      },
      expect: [],
    },
    context: {},
  });

  assert.equal(resolved.ok, true);
  if (!resolved.ok) {
    throw new Error("expected resolved watcher execution");
  }
  assert.equal(resolved.execution.responseBodyFormat, "auto");
});

test("summarizeWatcherObservation redacts response body and header values", () => {
  const summary = summarizeWatcherObservation({
    status: "pass",
    provider: {
      type: "http",
      protocol: "http",
      responseBodyFormat: "auto",
    },
    response: {
      statusCode: 200,
      body: "{\"state\":\"ready\"}",
      bodyFormat: "json",
      headers: {
        authorization: "secret",
        "content-type": "application/json",
      },
      bodyJson: { state: "ready" },
    },
    transport: {
      status: "pass",
      durationMs: 12,
      reasonCode: null,
    },
  });

  assert.deepEqual(summary.response.headerNames, ["authorization", "content-type"]);
  assert.equal(summary.response.hasBodyJson, true);
  assert.equal(typeof summary.response.bodyBytes, "number");
  assert.equal("body" in summary.response, false);
});
