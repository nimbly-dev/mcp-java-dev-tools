const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

const { executePerformanceRuntimeSuite } = require("@tools-regression-execution-plan-spec/performance_runtime_suite_executor.util");
const { buildPerformanceMstaSummary } = require("@tools-regression-execution-plan-spec/performance_msta_summary.util");

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");

function createTestTempDir(prefix: string): string {
  const base = path.join(REPO_ROOT, "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  const normalizedPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            const entry = workspace as Record<string, unknown>;
            const defaults =
              entry.defaults && typeof entry.defaults === "object"
                ? (entry.defaults as Record<string, unknown>)
                : {};
            return {
              ...entry,
              defaults: {
                ...defaults,
                orchestrator: {
                  resumePollMax: 30,
                  resumePollIntervalMs: 10000,
                  resumePollTimeoutMs: 300000,
                },
              },
            };
          }),
        }
      : payload;
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(normalizedPayload, null, 2)}\n`, "utf8");
}

function writePerformancePlan(
  root: string,
  projectName: string,
  planName: string,
  baseUrl: string,
  options?: {
    probeId?: string;
    workloadProvider?: Record<string, unknown>;
    requiredLineHits?: string[];
    executionTiming?: {
      enabled: true;
      provider: "async-profiler";
      event?: string;
      intervalNanos?: number;
      outputFormat?: "jfr";
    };
    msta?: {
      enabled?: boolean;
      mode?: "method_targets" | "target_plus_path";
      methodTargets?: Array<{ methodRef: string }>;
    };
  },
): void {
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
      requiredLineHits: options?.requiredLineHits ?? ["com.example.catalog.CatalogService#search:42"],
      ...(typeof options?.probeId === "string" ? { probeId: options.probeId } : {}),
    },
    ...(options?.workloadProvider ? { workloadProvider: options.workloadProvider } : {}),
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
    ...((options?.executionTiming || options?.msta)
      ? {
          analysis: {
            ...(options?.executionTiming ? { executionTiming: options.executionTiming } : {}),
            ...(options?.msta ? { msta: options.msta } : {}),
          },
        }
      : {}),
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

test("executePerformanceRuntimeSuite persists MSTA status as not_configured when analysis.msta is absent", async () => {
  const root = createTestTempDir("performance-runtime-suite-msta-not-configured");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-no-msta";
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
    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(out.planRuns[0].runId),
    );
    const executionResult = JSON.parse(fs.readFileSync(path.join(runDir, "execution.result.json"), "utf8"));
    const evidence = JSON.parse(fs.readFileSync(path.join(runDir, "evidence.json"), "utf8"));
    assert.deepEqual(executionResult.msta, { status: "not_configured" });
    assert.deepEqual(evidence.msta, { status: "not_configured" });
    assert.equal(fs.existsSync(path.join(runDir, "execution-timing.msta.json")), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite persists MSTA status as disabled when analysis.msta.enabled=false", async () => {
  const root = createTestTempDir("performance-runtime-suite-msta-disabled");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-msta-disabled";
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
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      msta: {
        enabled: false,
      },
    });

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
    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(out.planRuns[0].runId),
    );
    const executionResult = JSON.parse(fs.readFileSync(path.join(runDir, "execution.result.json"), "utf8"));
    const evidence = JSON.parse(fs.readFileSync(path.join(runDir, "evidence.json"), "utf8"));
    assert.deepEqual(executionResult.msta, { status: "disabled" });
    assert.deepEqual(evidence.msta, { status: "disabled" });
    assert.equal(fs.existsSync(path.join(runDir, "execution-timing.msta.json")), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite blocks explicit enabled MSTA without methodTargets", async () => {
  const root = createTestTempDir("performance-runtime-suite-msta-invalid");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-msta-invalid";
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
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      executionTiming: {
        enabled: true,
        provider: "async-profiler",
        outputFormat: "jfr",
      },
      msta: {
        enabled: true,
      },
    });

    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async ({ toolName }: { toolName: string }) => {
        throw new Error(`unexpected tool invocation: ${toolName}`);
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal(out.planRuns.length, 1);
    assert.equal(out.planRuns[0].status, "blocked");
    assert.equal(out.planRuns[0].blockedReasonCode, "performance_plan_invalid");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite blocks malformed present analysis.msta objects", async () => {
  const root = createTestTempDir("performance-runtime-suite-msta-malformed");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-msta-malformed";
    const planName = "catalog-search-perf";
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
              plans: [{ order: 1, planName }],
            },
          ],
        },
      ],
    });

    const cases: Array<{
      name: string;
      msta: unknown;
      expectedRequiredUserAction: string[];
    }> = [
      {
        name: "empty object",
        msta: {},
        expectedRequiredUserAction: ["Set analysis.msta.enabled=true or remove analysis.msta when MSTA is not configured."],
      },
      {
        name: "string enabled",
        msta: { enabled: "true" },
        expectedRequiredUserAction: ["Set analysis.msta.enabled=true or remove analysis.msta when MSTA is not configured."],
      },
    ];

    for (const testCase of cases) {
      writePerformancePlan(root, projectName, planName, "http://127.0.0.1:18082", {
        executionTiming: {
          enabled: true,
          provider: "async-profiler",
          outputFormat: "jfr",
        },
      });
      const contractPath = path.join(root, ".mcpjvm", projectName, "plans", "performance", planName, "contract.json");
      const contract = JSON.parse(fs.readFileSync(contractPath, "utf8"));
      contract.analysis.msta = testCase.msta;
      writeJson(contractPath, contract);

      const out = await executePerformanceRuntimeSuite({
        workspaceRootAbs: root,
        projectName,
        executionProfile,
        mcpInvoke: async ({ toolName }: { toolName: string }) => {
          throw new Error(`unexpected tool invocation for ${testCase.name}: ${toolName}`);
        },
      });

      assert.equal(out.status, "blocked");
      assert.equal(out.planRuns.length, 1);
      assert.equal(out.planRuns[0].status, "blocked");
      assert.equal(out.planRuns[0].blockedReasonCode, "performance_plan_invalid");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite resolves compatibility placeholder aliases in performance entrypoints", async () => {
  const root = createTestTempDir("performance-runtime-suite-placeholder-alias");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-placeholder-alias";
    const planName = "catalog-search-perf";
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
              plans: [
                {
                  order: 1,
                  planName,
                  providedContext: {
                    perfBaseUrl: "http://127.0.0.1:18082",
                    tenantId: "tenant-social-001",
                    "auth.bearer": "token-123",
                  },
                },
              ],
            },
          ],
        },
      ],
    });
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
            baseUrl: "{{ perfBaseUrl }}",
            wrappedOnly: true,
          },
          request: {
            method: "GET",
            path: "/work/{{{tenantId}}}",
            headers: {
              Authorization: "Bearer {{auth.bearer}}",
            },
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

    let transportCalls = 0;
    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          transportCalls += 1;
          const request = (input.request ?? {}) as Record<string, unknown>;
          assert.equal(request.url, "http://127.0.0.1:18082/work/tenant-social-001");
          assert.equal((request.headers as Record<string, unknown>).Authorization, "Bearer token-123");
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
    assert.equal(transportCalls > 0, true);
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
              env: {
                PATH: path.join(root, "missing-path"),
              },
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

test("executePerformanceRuntimeSuite runs workloadProvider=jmeter generated_http and persists JMeter artifacts", async () => {
  const root = createTestTempDir("performance-runtime-suite-jmeter");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-jmeter";
    const fakeJmeterHome = path.join(root, "fake-jmeter");
    const fakeJmeterBin = path.join(fakeJmeterHome, "bin");
    fs.mkdirSync(fakeJmeterBin, { recursive: true });
    const fakeRunnerJs = path.join(fakeJmeterBin, "fake-jmeter.js");
    fs.writeFileSync(
      fakeRunnerJs,
      [
        "const fs = require('node:fs');",
        "const path = require('node:path');",
        "const args = process.argv.slice(2);",
        "const jmx = args[args.indexOf('-t') + 1];",
        "const jtl = args[args.indexOf('-l') + 1];",
        "const log = args[args.indexOf('-j') + 1];",
        "if (!jmx || !jtl || !log) process.exit(2);",
        "const xml = fs.readFileSync(jmx, 'utf8');",
        "if (!xml.includes('HTTPSamplerProxy')) process.exit(3);",
        "fs.writeFileSync(jtl, [",
        "  'timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,url',",
        "  '1,10,HTTP Request,200,OK,thread-1,true,http://127.0.0.1:18082/work',",
        "  '2,20,HTTP Request,200,OK,thread-1,true,http://127.0.0.1:18082/work',",
        "  '3,30,HTTP Request,500,Server Error,thread-1,false,http://127.0.0.1:18082/work'",
        "].join('\\n'));",
        "fs.writeFileSync(log, 'fake-jmeter-ok\\n');",
      ].join("\n"),
      "utf8",
    );
    if (process.platform === "win32") {
      fs.writeFileSync(path.join(fakeJmeterBin, "jmeter.bat"), `@echo off\r\nnode "${fakeRunnerJs}" %*\r\n`, "utf8");
    } else {
      const shellPath = path.join(fakeJmeterBin, "jmeter");
      fs.writeFileSync(shellPath, `#!/bin/sh\nnode "${fakeRunnerJs}" "$@"\n`, "utf8");
      fs.chmodSync(shellPath, 0o755);
    }

    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile,
              suiteType: "performance",
              executionPolicy: "continue_on_fail",
              runtimeConfig: {
                requestTimeoutMs: 250,
              },
              plans: [{ order: 1, planName: "catalog-search-perf" }],
            },
          ],
        },
      ],
    });
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      probeId: "catalog-service",
      workloadProvider: {
        type: "jmeter",
        mode: "generated_http",
        options: {
          installationPath: fakeJmeterHome,
        },
      },
    });

    let transportCalls = 0;
    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          transportCalls += 1;
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

    assert.equal(out.status, "partial_fail");
    assert.equal(out.planRuns[0].runStatus, "fail");
    assert.equal(transportCalls, 0);
    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(out.planRuns[0].runId),
    );
    const execution = JSON.parse(fs.readFileSync(path.join(runDir, "execution.result.json"), "utf8"));
    assert.equal(execution.status, "fail");
    assert.equal(execution.workloadProvider.type, "jmeter");
    assert.equal(execution.workloadProviderArtifacts.jmxPathAbs.endsWith("workload.jmeter.jmx"), true);
    assert.equal(execution.metrics.totalRequests, 3);
    assert.equal(execution.metrics.failedRequests, 1);
    assert.equal(fs.existsSync(path.join(runDir, "workload.jmeter.jmx")), true);
    assert.equal(fs.existsSync(path.join(runDir, "workload.jmeter.jtl")), true);
    assert.equal(fs.existsSync(path.join(runDir, "workload.jmeter.log")), true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite blocks invalid workloadProvider for JMeter", async () => {
  const root = createTestTempDir("performance-runtime-suite-jmeter-invalid");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-jmeter-invalid";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile,
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "catalog-search-perf" }],
            },
          ],
        },
      ],
    });
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      workloadProvider: {
        type: "jmeter",
        mode: "custom_jmx",
      },
    });

    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async () => {
        throw new Error("unexpected tool invocation");
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal(out.planRuns[0].status, "blocked");
    assert.equal(out.planRuns[0].blockedReasonCode, "performance_workload_provider_invalid");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite redacts resolved secret context from persisted context artifact", async () => {
  const root = createTestTempDir("performance-runtime-suite-context-redaction");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-secret-redaction";
    const envFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(envFile), { recursive: true });
    fs.writeFileSync(envFile, "AUTH_BEARER_TOKEN=perf-secret-token\n", "utf8");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          executionProfiles: [
            {
              executionProfile,
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [
                {
                  order: 1,
                  planName: "catalog-search-perf",
                  providedContext: {
                    customHeader: "non-secret",
                    nested: {
                      authorization: "Bearer nested-secret",
                      token: "remove-me",
                    },
                  },
                },
              ],
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
    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(out.planRuns[0].runId),
    );
    const context = JSON.parse(fs.readFileSync(path.join(runDir, "context.resolved.json"), "utf8"));
    assert.deepEqual(context.redaction, {
      resolvedSecretKeyCount: 1,
      resolvedSecretKeysOmitted: ["auth.bearer"],
    });
    assert.equal(context.providedContext.customHeader, "non-secret");
    assert.equal(context.providedContext["auth.bearer"], undefined);
    assert.equal(context.providedContext.nested.authorization, undefined);
    assert.equal(context.providedContext.nested.token, undefined);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite propagates observationTargets.probeId to strict-line and profiler Probe calls", async () => {
  const root = createTestTempDir("performance-runtime-suite-probe-id");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-msta";
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
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      probeId: "catalog-service",
      executionTiming: {
        enabled: true,
        provider: "async-profiler",
        event: "cpu",
        outputFormat: "jfr",
      },
    });

    const probeCalls: Array<{ action: string; input: Record<string, unknown> }> = [];
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
          probeCalls.push({
            action: String(input.action ?? ""),
            input: ((input.input ?? {}) as Record<string, unknown>),
          });
          if (input.action === "reset") {
            return { structuredContent: { result: { reasonCode: "ok" } } };
          }
          if (input.action === "profiler") {
            const profilerInput = (input.input ?? {}) as Record<string, unknown>;
            if (profilerInput.action === "download") {
              return {
                structuredContent: {
                  result: {
                    status: "downloaded",
                    outputPath: String(profilerInput.outputPath ?? ""),
                  },
                },
              };
            }
            return { structuredContent: { status: "ok" } };
          }
          if (input.action === "wait_for_hit") {
            return { structuredContent: { result: { hit: true } } };
          }
        }
        throw new Error(`unexpected tool invocation: ${toolName}`);
      },
    });

    assert.equal(out.status, "pass");
    const resetCall = probeCalls.find((entry) => entry.action === "reset");
    const waitCall = probeCalls.find((entry) => entry.action === "wait_for_hit");
    const profilerCalls = probeCalls.filter((entry) => entry.action === "profiler");
    assert.ok(resetCall);
    assert.ok(waitCall);
    assert.equal(profilerCalls.length, 3);
    assert.equal(resetCall?.input.probeId, "catalog-service");
    assert.equal(waitCall?.input.probeId, "catalog-service");
    assert.equal(profilerCalls[0]?.input.probeId, "catalog-service");
    assert.equal(profilerCalls[1]?.input.probeId, "catalog-service");
    assert.equal(profilerCalls[2]?.input.probeId, "catalog-service");
    assert.equal(profilerCalls[2]?.input.action, "download");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite blocks immediately when profiler start reports unsupported runtime", async () => {
  const root = createTestTempDir("performance-runtime-suite-profiler-unsupported");
  try {
    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-profiler-unsupported";
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
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      executionTiming: {
        enabled: true,
        provider: "async-profiler",
        event: "wall",
        outputFormat: "jfr",
      },
      msta: {
        enabled: true,
        mode: "method_targets",
        methodTargets: [{ methodRef: "com.example.catalog.CatalogService#search" }],
      },
    });

    let transportCalls = 0;
    const out = await executePerformanceRuntimeSuite({
      workspaceRootAbs: root,
      projectName,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          transportCalls += 1;
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
          if (input.action === "profiler") {
            return {
              structuredContent: {
                response: {
                  status: 200,
                  json: {
                    contractVersion: "0.1.7",
                    ok: true,
                    action: "start",
                  },
                },
                result: {
                  status: "disabled",
                  provider: "async-profiler",
                  supported: false,
                  sessionId: "",
                  detail: "profiler_unsupported_platform",
                },
              },
            };
          }
        }
        throw new Error(`unexpected tool invocation: ${toolName}`);
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal(out.planRuns.length, 1);
    assert.equal(out.planRuns[0].status, "blocked");
    assert.equal(out.planRuns[0].blockedReasonCode, "profiler_unsupported_platform");
    assert.equal(transportCalls, 0);

    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(out.planRuns[0].runId),
    );
    const executionResult = JSON.parse(fs.readFileSync(path.join(runDir, "execution.result.json"), "utf8"));
    assert.equal(executionResult.status, "blocked");
    assert.equal(executionResult.reasonCode, "profiler_unsupported_platform");
    assert.equal(executionResult.executionTiming.result.supported, false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executePerformanceRuntimeSuite persists first MSTA-oriented output when execution-timing JFR is readable", async () => {
  const root = createTestTempDir("performance-runtime-suite-msta");
  const javaFixtureDir = fs.mkdtempSync(path.join(os.tmpdir(), "msta-jfr-"));
  try {
    const sourceFile = path.join(javaFixtureDir, "JfrShape.java");
    fs.writeFileSync(
      sourceFile,
      [
        "public class JfrShape {",
        "  public static void main(String[] args) throws Exception {",
        "    long end = System.currentTimeMillis() + 3000L;",
        "    long x = 0L;",
        "    while (System.currentTimeMillis() < end) {",
        "      x += controller();",
        "    }",
        "    System.out.println(x);",
        "  }",
        "  static long controller() { return service(); }",
        "  static long service() { return helper() + helper(); }",
        "  static long helper() { long sum = 0L; for (int i = 0; i < 10000; i++) sum += i; return sum; }",
        "}",
        "",
      ].join("\n"),
      "utf8",
    );
    const compiled = spawnSync("javac", [sourceFile], { windowsHide: true, encoding: "utf8" });
    assert.equal(compiled.status, 0, compiled.stderr);
    const jfrPath = path.join(javaFixtureDir, "execution-timing.jfr");
    const recorded = spawnSync(
      "java",
      ["-Xint", `-XX:StartFlightRecording=filename=${jfrPath},dumponexit=true,settings=profile`, "-cp", javaFixtureDir, "JfrShape"],
      { windowsHide: true, encoding: "utf8" },
    );
    assert.equal(recorded.status, 0, recorded.stderr);
    assert.equal(fs.existsSync(jfrPath), true);

    const projectName = "petclinic-performance";
    const executionProfile = "catalog-perf-msta-read";
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
    writePerformancePlan(root, projectName, "catalog-search-perf", "http://127.0.0.1:18082", {
      probeId: "catalog-service",
      requiredLineHits: ["JfrShape#controller:10"],
      executionTiming: {
        enabled: true,
        provider: "async-profiler",
        event: "cpu",
        outputFormat: "jfr",
      },
      msta: {
        enabled: true,
        mode: "target_plus_path",
        methodTargets: [{ methodRef: "JfrShape#controller" }],
      },
    });
    const expectedJfrInRunDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
    );
    let profilerDownloadAttempts = 0;

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
          if (input.action === "profiler") {
            const profilerInput = (input.input ?? {}) as Record<string, unknown>;
            if (profilerInput.action === "download") {
              profilerDownloadAttempts += 1;
              if (profilerDownloadAttempts === 1) {
                return {
                  structuredContent: {
                    response: {
                      status: 404,
                      json: {
                        error: "profiler_output_not_found",
                      },
                    },
                    result: {
                      status: "profiler_download_failed",
                    },
                  },
                };
              }
              fs.copyFileSync(jfrPath, String(profilerInput.outputPath));
              return {
                structuredContent: {
                  result: {
                    status: "downloaded",
                    outputPath: String(profilerInput.outputPath),
                  },
                },
              };
            }
            return {
              structuredContent: {
                result: {
                  status: "completed",
                  outputPath: jfrPath,
                },
              },
            };
          }
          if (input.action === "wait_for_hit") {
            return { structuredContent: { result: { hit: true } } };
          }
        }
        throw new Error(`unexpected tool invocation: ${toolName}`);
      },
    });

    assert.equal(out.status, "pass");
    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "runs",
      String(out.planRuns[0].runId),
    );
    assert.equal(runDir.startsWith(expectedJfrInRunDir), true);
    const mstaPath = path.join(runDir, "execution-timing.msta.json");
    assert.equal(fs.existsSync(mstaPath), true);
    assert.equal(fs.existsSync(path.join(runDir, "execution-timing.jfr")), true);
    assert.equal(profilerDownloadAttempts >= 2, true);
    const msta = JSON.parse(fs.readFileSync(mstaPath, "utf8"));
    assert.equal(msta.status, "available");
    assert.equal(msta.unit, "ms");
    assert.equal(msta.provider.name, "async-profiler");
    assert.equal(msta.provider.event, "cpu");
    assert.equal(msta.mode, "target_plus_path");
    assert.equal(Array.isArray(msta.methods), true);
    assert.equal(msta.methods.length > 0, true);
    assert.equal(msta.methods[0].methodRef, "JfrShape#controller");
    assert.equal(Array.isArray(msta.methods[0].pathSteps), true);
    assert.equal(msta.methods[0].pathSteps.length > 0, true);
    assert.equal(Array.isArray(msta.targets), true);
    assert.equal(msta.targets.length > 0, true);
    assert.equal(Array.isArray(msta.targets[0].steps), true);
    assert.equal(msta.targets[0].steps.length > 0, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
    fs.rmSync(javaFixtureDir, { recursive: true, force: true });
  }
});

test("buildPerformanceMstaSummary consumes profiler.WallClockSample events for MSTA anchoring", { concurrency: false }, async () => {
  const root = createTestTempDir("performance-msta-wall-clock");
  const fakeJfrPath = path.join(root, "execution-timing.jfr");
  const fakeBinDir = path.join(root, "fake-bin");
  fs.mkdirSync(fakeBinDir, { recursive: true });
  fs.writeFileSync(fakeJfrPath, "fake-jfr", "utf8");
  fs.writeFileSync(
    path.join(fakeBinDir, "jfr.js"),
    [
      "process.stdout.write(JSON.stringify({",
      "  type: 'profiler.WallClockSample',",
      "  samples: 7,",
      "  frames: [",
      "    'io.javatab.microservices.composite.course.MetricsController#hello',",
      "    'io.javatab.microservices.util.http.ApiUtil#ok'",
      "  ]",
      "}) + '\\n');",
    ].join("\n"),
    "utf8",
  );
  fs.writeFileSync(
    path.join(fakeBinDir, "jfr.cmd"),
    [
      "@echo off",
      `"${process.execPath}" "%~dp0jfr.js"`,
    ].join("\r\n"),
    "utf8",
  );

  const previousExtractor = process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR;
  process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR = path.join(fakeBinDir, "jfr.cmd");
  try {
    const summary = await buildPerformanceMstaSummary({
      requiredLineHits: ["io.javatab.microservices.composite.course.MetricsController#hello:52"],
      methodTargets: ["io.javatab.microservices.composite.course.MetricsController#hello"],
      mode: "method_targets",
      provider: {
        name: "async-profiler",
        event: "wall",
        outputFormat: "jfr",
      },
      durationMs: 7000,
      profilerStopResult: {
        result: {
          outputPath: fakeJfrPath,
        },
      },
      runDirAbs: root,
    });

    assert.equal(summary.status, "available");
    assert.deepEqual(summary.sourceEventTypes, ["profiler.WallClockSample"]);
    assert.equal(summary.mode, "method_targets");
    assert.equal(summary.methods.length, 1);
    assert.equal(summary.methods[0].methodRef, "io.javatab.microservices.composite.course.MetricsController#hello");
    assert.equal(summary.methods[0].samples, 7);
    assert.equal(summary.methods[0].pathSteps[0].methodRef, "io.javatab.microservices.composite.course.MetricsController#hello");
    assert.equal(summary.methods[0].pathSteps[0].samples, 7);
  } finally {
    if (typeof previousExtractor === "string") {
      process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR = previousExtractor;
    } else {
      delete process.env.MCP_JAVA_DEV_TOOLS_JFR_EXTRACTOR;
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
});
