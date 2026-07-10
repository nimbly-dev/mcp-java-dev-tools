const assert = require("node:assert/strict");
const test = require("node:test");

const { probeDiagnose } = require("@tools-feature-probe/domain");

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

async function withMockedFetch(
  mockFetch: typeof globalThis.fetch,
  fn: () => Promise<void>,
): Promise<void> {
  const originalFetch = globalThis.fetch;
  if (!originalFetch) throw new Error("global fetch is unavailable in this Node runtime");
  globalThis.fetch = mockFetch;
  try {
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

function readHeader(headers: RequestInit["headers"] | undefined, name: string): string | undefined {
  if (!headers) return undefined;
  const lowerName = name.toLowerCase();
  if (headers instanceof Headers) return headers.get(name) ?? undefined;
  if (Array.isArray(headers)) {
    const match = headers.find(([headerName]) => headerName.toLowerCase() === lowerName);
    return match?.[1];
  }
  for (const [headerName, value] of Object.entries(headers)) {
    if (headerName.toLowerCase() === lowerName) return String(value);
  }
  return undefined;
}

test("probe action=check forwards http.headers to reset and status calls", async () => {
  const calls: Array<{ url: string; method: string | undefined; headers: RequestInit["headers"] }> = [];

  await withMockedFetch(async (url, init) => {
    calls.push({
      url: String(url),
      method: init?.method,
      headers: init?.headers,
    });

    if (String(url).includes("/__probe/reset")) {
      return jsonResponse(200, { ok: true, key: "mcp.jvm.diagnose#key", contractVersion: "0.1.0" });
    }
    return jsonResponse(200, {
      key: "mcp.jvm.diagnose#key",
      hitCount: 0,
      lastHitEpoch: 0,
      contractVersion: "0.1.0",
    });
  }, async () => {
    const result = await probeDiagnose({
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      resetPath: "/__probe/reset",
      http: {
        headers: {
          Authorization: "Bearer fixture-token",
          "X-Probe-Client": "mcp-it",
        },
      },
    });

    assert.equal(result.structuredContent.config.authConfigured, true);
    assert.equal(result.structuredContent.checks.reset.ok, true);
    assert.equal(result.structuredContent.checks.status.ok, true);
    assert.equal(result.structuredContent.status, "ok");
  });

  assert.equal(calls.length, 2);
  const resetCall = calls[0];
  const statusCall = calls[1];
  if (!resetCall || !statusCall) throw new Error("missing expected probe action=check calls");

  assert.equal(resetCall.method, "POST");
  assert.equal(readHeader(resetCall.headers, "authorization"), "Bearer fixture-token");
  assert.equal(readHeader(resetCall.headers, "x-probe-client"), "mcp-it");
  assert.equal(readHeader(resetCall.headers, "content-type"), "application/json");

  assert.equal(statusCall.method, "GET");
  assert.equal(readHeader(statusCall.headers, "authorization"), "Bearer fixture-token");
  assert.equal(readHeader(statusCall.headers, "x-probe-client"), "mcp-it");
});

test("probe action=check surfaces deterministic guidance for protected endpoints", async () => {
  await withMockedFetch(async (url) => {
    if (String(url).includes("/__probe/reset")) {
      return jsonResponse(401, { error: "unauthorized" });
    }
    return jsonResponse(401, { error: "unauthorized" });
  }, async () => {
    const result = await probeDiagnose({
      baseUrl: "http://127.0.0.1:9191",
      statusPath: "/__probe/status",
      resetPath: "/__probe/reset",
      http: {
        headers: {
          Authorization: "Bearer invalid-token",
        },
      },
    });

    assert.equal(result.structuredContent.checks.reset.ok, false);
    assert.equal(result.structuredContent.checks.status.ok, false);
    assert.equal(result.structuredContent.status, "diagnose_failed");
    assert.equal(result.structuredContent.reasonCode, "diagnose_failed");
    assert.equal(result.structuredContent.nextActionCode, "resolve_probe_diagnostics");
    assert.equal((result.structuredContent.reasonMeta as any).failedStep, "probe_diagnostics");
    assert.equal(result.structuredContent.checks.status.keyDecodingOk, undefined);
    assert.equal(
      result.structuredContent.recommendations.includes(
        "Probe reset endpoint is protected. Provide auth headers via probe.input.http.headers.",
      ),
      true,
    );
    assert.equal(
      result.structuredContent.recommendations.includes(
        "Probe status endpoint is protected. Provide auth headers via probe.input.http.headers.",
      ),
      true,
    );
  });
});
