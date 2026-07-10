const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");

const { transportExecuteDomain } = require("@tools-feature-transport-execution");

async function createServer(statusCode: number, body: string) {
  const server = http.createServer((_req: any, res: any) => {
    res.statusCode = statusCode;
    res.setHeader("content-type", "application/json");
    res.end(body);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  return { server, url: `http://127.0.0.1:${port}/test` };
}

test("transport_execute fails closed on wrapper policy violation", async () => {
  const out = await transportExecuteDomain({
    protocol: "http",
    request: { method: "GET", url: "http://127.0.0.1:1" },
    wrappedOnly: true,
    allowNonWrappedExecutable: true,
  });
  assert.equal(out.structuredContent.status, "blocked_invalid");
  assert.equal(out.structuredContent.reasonCode, "wrapper_policy_violation");
});

test("transport_execute executes wrapped http request successfully", async () => {
  const { server, url } = await createServer(200, '{"ok":true}');
  try {
    const out = await transportExecuteDomain({
      protocol: "http",
      request: { method: "GET", url },
      wrappedOnly: true,
      allowNonWrappedExecutable: false,
    });
    assert.equal(out.structuredContent.status, "pass");
    assert.equal(out.structuredContent.statusCode, 200);
    assert.equal("body" in out.structuredContent, false);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("transport_execute returns fail_http for non-2xx", async () => {
  const { server, url } = await createServer(401, '{"error":"unauthorized"}');
  try {
    const out = await transportExecuteDomain({
      protocol: "http",
      request: { method: "GET", url },
      wrappedOnly: true,
      allowNonWrappedExecutable: false,
    });
    assert.equal(out.structuredContent.status, "fail_http");
    assert.equal(out.structuredContent.statusCode, 401);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("transport_execute serializes object body and sets json content-type when absent", async () => {
  let capturedBody = "";
  let capturedContentType = "";
  const server = http.createServer((req: any, res: any) => {
    capturedContentType = String(req.headers["content-type"] ?? "");
    req.setEncoding("utf8");
    req.on("data", (chunk: string) => {
      capturedBody += chunk;
    });
    req.on("end", () => {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end('{"ok":true}');
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const url = `http://127.0.0.1:${address.port}/post`;
    const out = await transportExecuteDomain({
      protocol: "http",
      request: {
        method: "POST",
        url,
        body: { id: "test", structure: null },
      },
      wrappedOnly: true,
      allowNonWrappedExecutable: false,
    });
    assert.equal(out.structuredContent.status, "pass");
    assert.equal(capturedContentType.startsWith("application/json"), true);
    assert.equal(capturedBody, JSON.stringify({ id: "test", structure: null }));
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("transport_execute preserves explicit request timeout above 120s without truncation", async () => {
  const originalFetch = global.fetch;
  const originalSetTimeout = global.setTimeout;
  const originalClearTimeout = global.clearTimeout;
  let capturedTimeoutMs = 0;
  try {
    global.setTimeout = ((handler: (...args: any[]) => void, timeout?: number, ...args: any[]) => {
      capturedTimeoutMs = Number(timeout ?? 0);
      return originalSetTimeout(handler, 0, ...args);
    }) as typeof setTimeout;
    global.clearTimeout = ((handle: any) => originalClearTimeout(handle)) as typeof clearTimeout;
    global.fetch = (async () => ({
      status: 200,
      headers: new Headers(),
      text: async () => '{"ok":true}',
    })) as unknown as typeof fetch;

    const out = await transportExecuteDomain({
      protocol: "http",
      request: { method: "GET", url: "http://127.0.0.1:8080/test", timeoutMs: 300000 },
      wrappedOnly: true,
      allowNonWrappedExecutable: false,
    });

    assert.equal(out.structuredContent.status, "pass");
    assert.equal(capturedTimeoutMs, 300000);
  } finally {
    global.fetch = originalFetch;
    global.setTimeout = originalSetTimeout;
    global.clearTimeout = originalClearTimeout;
  }
});

