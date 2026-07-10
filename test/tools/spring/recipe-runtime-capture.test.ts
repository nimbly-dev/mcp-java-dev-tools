const assert = require("node:assert/strict");
const test = require("node:test");

const {
  enrichRuntimeCapture,
} = require("@tools-feature-route-synthesis");

test("enrichRuntimeCapture returns unavailable when key/line is missing", async () => {
  const out = await enrichRuntimeCapture({
    inferredKey: undefined,
    inferredLine: undefined,
    probeBaseUrl: "http://127.0.0.1:9191",
    probeStatusPath: "/__probe/status",
  });
  assert.equal(out.status, "unavailable");
  assert.equal(out.reason, "probe_key_or_line_missing");
});

test("enrichRuntimeCapture returns available when capturePreview is present", async () => {
  const out = await enrichRuntimeCapture({
    inferredKey: "com.example.Catalog#save",
    inferredLine: 88,
    probeBaseUrl: "http://127.0.0.1:9191",
    probeStatusPath: "/__probe/status",
    probeStatusFn: async () => ({
      content: [{ type: "text", text: "{}" }],
      structuredContent: {
        response: {
          status: 200,
          json: {
            contractVersion: "0.1.0",
            hitCount: 1,
            capturePreview: {
              available: true,
              captureId: "abc123",
              executionPaths: ["CatalogController.listCatalogShoes()#42"],
            },
          },
        },
      },
    }),
  });
  assert.equal(out.status, "available");
  assert.equal(out.capturePreview.captureId, "abc123");
  assert.equal(out.capturePreview.executionPaths, undefined);
});

test("enrichRuntimeCapture includes executionPaths when env is enabled", async () => {
  const previous = process.env.MCP_PROBE_INCLUDE_EXECUTION_PATHS;
  process.env.MCP_PROBE_INCLUDE_EXECUTION_PATHS = "true";
  try {
    const out = await enrichRuntimeCapture({
      inferredKey: "com.example.Catalog#save",
      inferredLine: 88,
      probeBaseUrl: "http://127.0.0.1:9191",
      probeStatusPath: "/__probe/status",
      probeStatusFn: async () => ({
        content: [{ type: "text", text: "{}" }],
        structuredContent: {
          response: {
            status: 200,
            json: {
              hitCount: 1,
              capturePreview: {
                available: true,
                captureId: "abc123",
                executionPaths: ["CatalogController.listCatalogShoes()#42"],
              },
            },
          },
        },
      }),
    });
    assert.equal(out.status, "available");
    assert.deepEqual(out.capturePreview.executionPaths, [
      "CatalogController.listCatalogShoes()#42",
    ]);
  } finally {
    if (typeof previous === "string") process.env.MCP_PROBE_INCLUDE_EXECUTION_PATHS = previous;
    else delete process.env.MCP_PROBE_INCLUDE_EXECUTION_PATHS;
  }
});

test("enrichRuntimeCapture returns not_captured_yet when preview is absent", async () => {
  const out = await enrichRuntimeCapture({
    inferredKey: "com.example.Catalog#save",
    inferredLine: 88,
    probeBaseUrl: "http://127.0.0.1:9191",
    probeStatusPath: "/__probe/status",
    probeStatusFn: async () => ({
      content: [{ type: "text", text: "{}" }],
      structuredContent: {
        response: {
          status: 200,
          json: {
            hitCount: 1,
            capturePreview: {
              available: false,
            },
          },
        },
      },
    }),
  });
  assert.equal(out.status, "not_captured_yet");
  assert.equal(out.reason, "status_checked_but_capture_unavailable");
  assert.equal((out as any).capturePreview, undefined);
});

test("enrichRuntimeCapture returns unavailable on probe status errors", async () => {
  const out = await enrichRuntimeCapture({
    inferredKey: "com.example.Catalog#save",
    inferredLine: 88,
    probeBaseUrl: "http://127.0.0.1:9191",
    probeStatusPath: "/__probe/status",
    probeStatusFn: async () => {
      throw new Error("unreachable");
    },
  });
  assert.equal(out.status, "unavailable");
  assert.equal(out.reason, "unreachable");
});
