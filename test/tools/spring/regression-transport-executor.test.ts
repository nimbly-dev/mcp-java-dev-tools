const assert = require("node:assert/strict");
const test = require("node:test");

const {
  createMcpWrappedTransportAdapter,
  createTransportRegistry,
  executeTransportWithRegistry,
} = require("@tools-regression-execution-plan-spec/regression_transport_executor.util");

test("mcp wrapped transport adapter returns pass for 2xx response", async () => {
  const adapter = createMcpWrappedTransportAdapter(async () => ({
    structuredContent: {
      status: "pass",
      statusCode: 200,
      durationMs: 25,
      bodyPreview: '{"ok":true}',
    },
  }));

  const result = await adapter.execute({
    protocol: "http",
    payload: { method: "GET", url: "http://localhost:9001/api/courses" },
  });

  assert.equal(result.status, "pass");
  assert.equal(result.statusCode, 200);
  assert.match(result.bodyPreview, /"ok":true/);
  assert.equal(typeof result.durationMs, "number");
  assert.equal(result.durationMs >= 1, true);
});

test("mcp wrapped transport adapter returns fail_http for non-2xx response", async () => {
  const adapter = createMcpWrappedTransportAdapter(async () => ({
    structuredContent: {
      status: "fail_http",
      statusCode: 401,
      durationMs: 10,
      bodyPreview: '{"error":"unauthorized"}',
    },
  }));

  const result = await adapter.execute({
    protocol: "http",
    payload: { method: "GET", url: "http://localhost:9001/api/courses" },
  });

  assert.equal(result.status, "fail_http");
  assert.equal(result.statusCode, 401);
});

test("mcp wrapped transport adapter fails closed for invalid payload", async () => {
  const adapter = createMcpWrappedTransportAdapter(async () => ({ structuredContent: {} }));
  const result = await adapter.execute({
    protocol: "http",
    payload: { method: "GET" },
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "http_payload_invalid");
  assert.deepEqual(result.reasonMeta?.missingFields, ["url"]);
  assert.equal(result.reasonMeta?.cause, "url_missing");
});

test("mcp wrapped transport adapter reports apiBaseUrl synthesis gap for relative pathTemplate without url", async () => {
  const adapter = createMcpWrappedTransportAdapter(async () => ({ structuredContent: {} }));
  const result = await adapter.execute({
    protocol: "http",
    payload: { method: "GET", pathTemplate: "/api/v1/posts" },
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "http_payload_invalid");
  assert.deepEqual(result.reasonMeta?.missingFields, ["url"]);
  assert.equal(result.reasonMeta?.cause, "api_base_url_missing_for_path_template");
  assert.equal(result.reasonMeta?.pathTemplate, "/api/v1/posts");
});

test("mcp wrapped transport adapter fails closed when wrapper response is missing", async () => {
  const adapter = createMcpWrappedTransportAdapter(async () => ({}));
  const result = await adapter.execute({
    protocol: "http",
    payload: { method: "GET", url: "http://localhost:9001/api/courses" },
  });
  assert.equal(result.status, "blocked_runtime");
  assert.equal(result.reasonCode, "transport_wrapper_missing_response");
});

test("registry executor fails closed when protocol is unsupported", async () => {
  const registry = createTransportRegistry([]);
  const result = await executeTransportWithRegistry({
    protocol: "grpc",
    payload: {},
    registry,
  });
  assert.equal(result.status, "blocked_invalid");
  assert.equal(result.reasonCode, "transport_not_supported");
});

