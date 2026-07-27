const assert = require("node:assert/strict");
const test = require("node:test");

const { resolveRequestMappingFromRuntime } = require("@/lib/request_mapping_runtime_resolver");

async function withMockedFetch(mockFetch: typeof globalThis.fetch, fn: () => Promise<void>) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = mockFetch;
  try {
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

test("[UT][transport][request_mapping_runtime_resolver] runtime mappings resolver extracts Spring handler mapping candidate", async () => {
  await withMockedFetch(
    async () => {
      return new Response(
        JSON.stringify({
          contexts: {
            application: {
              mappings: {
                dispatcherServlets: {
                  dispatcherServlet: [
                    {
                      handler: "com.example.HealthController#health()",
                      details: {
                        requestMappingConditions: {
                          methods: ["GET"],
                          patterns: ["/v1/health"],
                        },
                      },
                    },
                  ],
                },
              },
            },
          },
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    },
    async () => {
      const result = await resolveRequestMappingFromRuntime({
        mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
        classHint: "com.example.HealthController",
        methodHint: "health",
      });

      assert.equal(result.status, "ok");
      assert.equal(result.requestCandidate.method, "GET");
      assert.equal(result.requestCandidate.path, "/v1/health");
      assert.equal(result.evidence.includes("mapping_source=runtime_actuator"), true);
    },
  );
});

test("[UT][transport][request_mapping_runtime_resolver] runtime mappings resolver fails closed for unauthorized endpoint", async () => {
  await withMockedFetch(
    async () => {
      return new Response("forbidden", { status: 403 });
    },
    async () => {
      const result = await resolveRequestMappingFromRuntime({
        mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
        classHint: "com.example.HealthController",
        methodHint: "health",
      });

      assert.equal(result.status, "report");
      assert.equal(result.reasonCode, "runtime_mappings_unauthorized");
      assert.equal(result.failedStep, "runtime_mapping_fetch");
    },
  );
});

test("[UT][transport][request_mapping_runtime_resolver] runtime mappings resolver fails closed when multiple runtime routes match handler", async () => {
  await withMockedFetch(
    async () => {
      return new Response(
        JSON.stringify({
          contexts: {
            application: {
              mappings: {
                dispatcherServlets: {
                  dispatcherServlet: [
                    {
                      handler: "com.example.HealthController#health()",
                      details: {
                        requestMappingConditions: {
                          methods: ["GET"],
                          patterns: ["/v1/health"],
                        },
                      },
                    },
                    {
                      handler: "com.example.HealthController#health()",
                      details: {
                        requestMappingConditions: {
                          methods: ["GET"],
                          patterns: ["/v2/health"],
                        },
                      },
                    },
                  ],
                },
              },
            },
          },
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    },
    async () => {
      const result = await resolveRequestMappingFromRuntime({
        mappingsBaseUrl: "http://127.0.0.1:8080/actuator/mappings",
        classHint: "com.example.HealthController",
        methodHint: "health",
      });

      assert.equal(result.status, "report");
      assert.equal(result.reasonCode, "runtime_mapping_ambiguous");
      assert.equal(result.failedStep, "runtime_mapping_match");
    },
  );
});

test("[UT][transport][request_mapping_runtime_resolver] runtime mappings resolver accepts actuator base URL and normalizes to /actuator/mappings", async () => {
  let seenUrl = "";
  await withMockedFetch(
    async (input) => {
      seenUrl = String(input);
      return new Response(
        JSON.stringify({
          contexts: {
            application: {
              mappings: {
                dispatcherServlets: {
                  dispatcherServlet: [
                    {
                      handler: "com.example.HealthController#health()",
                      details: {
                        requestMappingConditions: {
                          methods: ["GET"],
                          patterns: ["/v1/health"],
                        },
                      },
                    },
                  ],
                },
              },
            },
          },
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      );
    },
    async () => {
      const result = await resolveRequestMappingFromRuntime({
        mappingsBaseUrl: "http://127.0.0.1:8080/actuator",
        classHint: "com.example.HealthController",
        methodHint: "health",
      });

      assert.equal(result.status, "ok");
      assert.equal(seenUrl, "http://127.0.0.1:8080/actuator/mappings");
    },
  );
});
