const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executePerformanceRuntimeSuite } = require("@tools-regression-execution-plan-spec/performance_runtime_suite_executor.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function writePerformancePlan(root: string, projectName: string, planName: string, baseUrl: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "performance", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "0.1.0",
    suiteType: "performance",
    execution: {
      intent: "performance",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    entrypoints: [
      {
        transport: {
          protocol: "http",
          baseUrl,
          wrappedOnly: true,
        },
        request: {
          method: "GET",
          path: "/work",
        },
      },
    ],
    observationTargets: {
      requiredLineHits: ["com.example.catalog.CatalogService#search:42"],
    },
    loadModel: {
      mode: "concurrency",
      concurrency: 1,
      rampUpSeconds: 0,
      durationSeconds: 1,
    },
    successCriteria: {
      maxErrorRatePct: 0,
      minThroughputPerSec: 0.5,
      p95LatencyMs: 100,
    },
  });
}

test("executePerformanceRuntimeSuite executes a performance plan and persists run artifacts", async () => {
  const root = createTestTempDir("performance-runtime-suite-pass");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-smoke";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile,
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              runtimeConfig: {
                requestTimeoutMs: 250,
              },
              plans: [{ order: 1, planName: "catalog-search-perf" }],
            },
          ],
        },
      ],
    });
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082");

    let transportCalls = 0;
    let resetCalls = 0;
    let waitCalls = 0;
    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          transportCalls += 1;
          const request = (input.request ?? {}) as Record<string, unknown>;
          assert.equal(request.method, "GET");
          assert.equal(request.url, "http://127.0.0.1:18082/work");
          assert.equal(request.timeoutMs, 250);
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 15,
              bodyPreview: "{\"ok\":true}",
            },
          };
        }
        if (toolName === "probe") {
          if (input.action === "reset") {
            resetCalls += 1;
            return {
              structuredContent: {
                result: {
                  reasonCode: "ok",
                },
              },
            };
          }
          if (input.action === "wait_for_hit") {
            waitCalls += 1;
            return {
              structuredContent: {
                result: {
                  hit: true,
                },
              },
            };
          }
        }
        throw new Error(`unexpected tool invocation: ${toolName}`);
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(out.planRuns.length, 1);
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[0].runStatus, "pass");
    assert.equal(transportCalls > 0, true);
    assert.equal(resetCalls, 1);
    assert.equal(waitCalls, 1);

    const runId = out.planRuns[0].runId;
    assert.ok(runId);
    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(runId),
    );
    assert.equal(fs.existsSync(path.join(runDir, "context.resolved.json")), true);
    assert.equal(fs.existsSync(path.join(runDir, "execution.result.json")), true);
    assert.equal(fs.existsSync(path.join(runDir, "evidence.json")), true);

    const execution = JSON.parse(fs.readFileSync(path.join(runDir, "execution.result.json"), "utf8"));
    assert.equal(execution.status, "pass");
    assert.equal(execution.metrics.failedRequests, 0);
    assert.equal(execution.requiredLineHits[0].hit, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite supports profile scriptRefs through shared project-context resolution", async () => {
  const root = createTestTempDir("performance-runtime-suite-script-refs");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-smoke";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "perf-setup",
              phase: "preRuntime",
              command: "node",
              args: ["-e", "process.exit(0)"],
              appdir: ".",
            },
          ],
          executionProfiles: [
            {
              executionProfile,
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              runtimeConfig: {
                requestTimeoutMs: 250,
              },
              scriptRefs: [{ name: "perf-setup" }],
              plans: [{ order: 1, planName: "catalog-search-perf" }],
            },
          ],
        },
      ],
    });
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082");

    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 15,
            },
          };
        }
        if (toolName === "probe") {
          if (input.action === "reset") {
            return { structuredContent: { result: { reasonCode: "ok" } } };
          }
          if (input.action === "wait_for_hit") {
            return { structuredContent: { result: { hit: true } } };
          }
        }
        throw new Error(`unexpected tool invocation: ${toolName}`);
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(out.planRuns.length, 1);
    assert.equal(out.planRuns[0].runStatus, "pass");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
