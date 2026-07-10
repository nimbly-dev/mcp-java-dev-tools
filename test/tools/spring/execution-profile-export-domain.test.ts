const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executionProfileExportDomain } = require("@/tools/core/execution_profile_export/domain");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
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

function writeJsonWithBom(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `\ufeff${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function writeProject(root: string): void {
  const projectName = "test-project";
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "setup-js",
              phase: "prePlan",
              command: "node",
              args: [".mcpjvm/test-project/scripts/setup.js"],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-js", phase: "prePlan" }],
              plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
            },
            {
            executionProfile: "alternate-run",
            executionPolicy: "continue_on_fail",
            plans: [{ order: 1, planName: "alternate-spec" }],
          },
        ],
      },
    ],
  });
  writePlanContract(root, "gateway-route-smoke-spec", projectName);
  writePlanContract(root, "alternate-spec", projectName);
}

function writeProjectWithName(root: string, projectName: string, executionProfile: string, planName: string): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: root,
        executionProfiles: [
          {
            executionProfile,
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName }],
          },
        ],
      },
    ],
  });
}

function writePlanContract(root: string, planName: string, projectName = "test-project"): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "metadata.json"), {
    execution: { intent: "regression" },
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [],
    steps: [
      {
        order: 1,
        id: "health_check",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            url: "http://127.0.0.1:8080/actuator/health",
          },
        },
        expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
      },
    ],
  });
}

function writePlanArtifact(root: string, planName: string, contract: Record<string, unknown>, projectName = "test-project"): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "metadata.json"), {
    execution: { intent: "regression" },
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"), contract);
}

function writePerformanceProject(root: string): void {
  const projectName = "test-performance-project";
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: root,
        executionProfiles: [
          {
            executionProfile: "type-performance-hello-suite",
            suiteType: "performance",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "type-performance-hello" }],
          },
        ],
      },
    ],
  });
  writePerformancePlanContract(root, projectName, "type-performance-hello");
}

function writePerformancePlanContract(root: string, projectName: string, planName: string): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", planName, "metadata.json"), {
    suiteType: "performance",
    execution: { intent: "performance" },
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", planName, "contract.json"), {
    entrypoints: [
      {
        transport: {
          protocol: "http",
          baseUrl: "http://127.0.0.1:8080",
          healthCheckPath: "/actuator/health",
          wrappedOnly: true,
        },
        request: {
          method: "GET",
          path: "/api/metrics/hello",
        },
      },
    ],
    observationTargets: {
      probeId: "composite-service",
      requiredLineHits: ["io.example.MetricsController#hello:52"],
    },
    loadModel: {
      mode: "concurrency",
      concurrency: 10,
      rampUpSeconds: 2,
      durationSeconds: 30,
    },
    successCriteria: {
      maxErrorRatePct: 1,
      minThroughputPerSec: 5,
      p95LatencyMs: 1200,
    },
    analysis: {
      executionTiming: {
        enabled: true,
        provider: "async-profiler",
        event: "wall",
        outputFormat: "jfr",
      },
    },
  });
}

function writePerformanceJmeterPlanContract(root: string, projectName: string, planName: string): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", planName, "metadata.json"), {
    suiteType: "performance",
    execution: { intent: "performance" },
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", planName, "contract.json"), {
    entrypoints: [
      {
        transport: {
          protocol: "http",
          baseUrl: "http://127.0.0.1:8080",
          healthCheckPath: "/actuator/health",
          wrappedOnly: true,
        },
        request: {
          method: "GET",
          path: "/api/metrics/hello",
        },
      },
    ],
    workloadProvider: {
      type: "jmeter",
      mode: "generated_http",
      options: {
        installationPath: "C:/tools/apache-jmeter-5.6.3",
      },
    },
    observationTargets: {
      probeId: "composite-service",
      requiredLineHits: ["io.example.MetricsController#hello:52"],
    },
    loadModel: {
      mode: "concurrency",
      concurrency: 10,
      rampUpSeconds: 2,
      durationSeconds: 30,
    },
    successCriteria: {
      maxErrorRatePct: 1,
      minThroughputPerSec: 5,
      p95LatencyMs: 1200,
    },
  });
}

function writePerformanceProjectWithExplicitProbeBaseUrl(root: string): void {
  const projectName = "test-performance-project-explicit-probe";
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: root,
        executionProfiles: [
          {
            executionProfile: "type-performance-review-suite",
            suiteType: "performance",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "type-performance-review-by-course" }],
          },
        ],
      },
    ],
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", "type-performance-review-by-course", "metadata.json"), {
    suiteType: "performance",
    execution: { intent: "performance" },
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", "type-performance-review-by-course", "contract.json"), {
    targets: [
      {
        type: "class_method",
        runtimeVerification: {
          strictProbeKey: "io.example.ReviewService#getReviewsByCourseId:54",
          probeId: "review-service",
          baseUrl: "http://127.0.0.1:9194",
        },
      },
    ],
    entrypoints: [
      {
        transport: {
          protocol: "http",
          baseUrl: "http://127.0.0.1:9000",
          healthCheckPath: "/actuator/health",
          wrappedOnly: true,
        },
        request: {
          method: "GET",
          path: "/reviews?course=1",
        },
      },
    ],
    observationTargets: {
      probeId: "review-service",
      baseUrl: "http://127.0.0.1:9194",
      requiredLineHits: ["io.example.ReviewService#getReviewsByCourseId:54"],
    },
    loadModel: {
      mode: "concurrency",
      concurrency: 10,
      rampUpSeconds: 2,
      durationSeconds: 30,
    },
    successCriteria: {
      maxErrorRatePct: 1,
      minThroughputPerSec: 5,
      p95LatencyMs: 1200,
    },
  });
}

function writeProbeConfig(root: string): void {
  writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
    defaultProfile: "workspace-default",
    profiles: {
      "workspace-default": {
        probes: {
          "composite-service": {
            baseUrl: "http://127.0.0.1:9195",
          },
        },
      },
    },
  });
}

function writeSuiteRunResult(
  root: string,
  projectName: string,
  suiteRunId: string,
  payload: Record<string, unknown>,
): void {
  writeJson(
    path.join(root, ".mcpjvm", projectName, "suite-runs", suiteRunId, "execution_orchestration.result.json"),
    payload,
  );
}

function readJson(filePath: string): any {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function writeFakeJmeterInstallation(root: string): string {
  const fakeJmeterHome = path.join(root, "fake-jmeter");
  const fakeJmeterBin = path.join(fakeJmeterHome, "bin");
  fs.mkdirSync(fakeJmeterBin, { recursive: true });
  const fakeRunnerJs = path.join(fakeJmeterBin, "fake-jmeter.js");
  fs.writeFileSync(
    fakeRunnerJs,
    [
      "const fs = require('node:fs');",
      "const args = process.argv.slice(2);",
      "const jmx = args[args.indexOf('-t') + 1];",
      "const jtl = args[args.indexOf('-l') + 1];",
      "const log = args[args.indexOf('-j') + 1];",
      "if (!jmx || !jtl || !log) process.exit(2);",
      "const xml = fs.readFileSync(jmx, 'utf8');",
      "if (!xml.includes('HTTPSamplerProxy')) process.exit(3);",
      "fs.writeFileSync(jtl, [",
      "  'timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,url',",
      "  '1,10,HTTP Request,200,OK,thread-1,true,http://127.0.0.1:8080/api/metrics/hello',",
      "  '2,20,HTTP Request,200,OK,thread-1,true,http://127.0.0.1:8080/api/metrics/hello',",
      "  '3,30,HTTP Request,200,OK,thread-1,true,http://127.0.0.1:8080/api/metrics/hello'",
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
  return fakeJmeterHome;
}

test("executionProfileExportDomain resolves executionProfile and creates a fresh sh export", async () => {
  const root = createTestTempDir("execution-profile-export-domain-sh");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "sh");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-regression-test-run$/);
    assert.match(
      String(out.structuredContent.output?.scriptPathAbs ?? ""),
      /exports[\\/]\d{4}-\d{2}-\d{2}-[0-9a-f-]+[\\/]run-execution-profile\.sh$/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain derives blocked source run status from latest canonical suite when no export summary exists", async () => {
  const root = createTestTempDir("execution-profile-export-domain-suite-source");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");
    writeSuiteRunResult(root, "test-project", "06-13-2026-12-39-27PM", {
      resultType: "execution_orchestration",
      action: "execute",
      projectName: "test-project",
      executionProfile: "regression-test-run",
      status: "blocked",
      suiteRunId: "06-13-2026-12-39-27PM",
      executionPolicy: "stop_on_fail",
      planRuns: [
        {
          order: 1,
          planName: "gateway-route-smoke-spec",
          status: "blocked",
          blockedReasonCode: "external_healthcheck_failed",
        },
      ],
      completedPlanCount: 1,
    });

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "ps1",
    });

    assert.equal(out.structuredContent.status, "ok");
    const readmePathAbs = String(out.structuredContent.output?.readmePathAbs ?? "");
    const scriptPathAbs = String(out.structuredContent.output?.scriptPathAbs ?? "");
    const readme = fs.readFileSync(readmePathAbs, "utf8");
    const script = fs.readFileSync(scriptPathAbs, "utf8");
    assert.match(readme, /SourceRunStatus: `blocked`/);
    assert.match(script, /# SourceRunStatus: blocked/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain uses real suite artifact time for when-based source selection", async () => {
  const root = createTestTempDir("execution-profile-export-domain-when-source");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const olderSuitePath = path.join(
      root,
      ".mcpjvm",
      "test-project",
      "suite-runs",
      "06-13-2026-12-39-27PM",
      "execution_orchestration.result.json",
    );
    writeSuiteRunResult(root, "test-project", "06-13-2026-12-39-27PM", {
      resultType: "execution_orchestration",
      action: "execute",
      projectName: "test-project",
      executionProfile: "regression-test-run",
      status: "blocked",
      suiteRunId: "06-13-2026-12-39-27PM",
      executionPolicy: "stop_on_fail",
      planRuns: [{ order: 1, planName: "gateway-route-smoke-spec", status: "blocked", blockedReasonCode: "probe_gate_failed" }],
      completedPlanCount: 1,
    });
    fs.utimesSync(olderSuitePath, new Date("2026-06-13T04:39:27.000Z"), new Date("2026-06-13T04:39:27.000Z"));

    const newerSuitePath = path.join(
      root,
      ".mcpjvm",
      "test-project",
      "suite-runs",
      "06-13-2026-12-51-12PM",
      "execution_orchestration.result.json",
    );
    writeSuiteRunResult(root, "test-project", "06-13-2026-12-51-12PM", {
      resultType: "execution_orchestration",
      action: "execute",
      projectName: "test-project",
      executionProfile: "regression-test-run",
      status: "pass",
      suiteRunId: "06-13-2026-12-51-12PM",
      executionPolicy: "stop_on_fail",
      planRuns: [{ order: 1, planName: "gateway-route-smoke-spec", status: "executed", runStatus: "pass", runId: "run-a" }],
      completedPlanCount: 1,
    });
    fs.utimesSync(newerSuitePath, new Date("2026-06-13T04:51:12.000Z"), new Date("2026-06-13T04:51:12.000Z"));

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      when: "2026-06-13T04:40:00.000Z",
      mode: "ps1",
    });

    assert.equal(out.structuredContent.status, "ok");
    const readmePathAbs = String(out.structuredContent.output?.readmePathAbs ?? "");
    const readme = fs.readFileSync(readmePathAbs, "utf8");
    assert.match(readme, /SourceRunStatus: `blocked`/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when mode/type is missing", async () => {
  const root = createTestTempDir("execution-profile-export-domain-mode-required");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
    });
    assert.equal(out.structuredContent.status, "execution_export_mode_required");
    assert.equal(out.structuredContent.reasonCode, "execution_export_mode_required");
    assert.equal(out.structuredContent.reasonMeta.nextAction, "provide mode=ps1|sh|postman");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when mode/type conflict", async () => {
  const root = createTestTempDir("execution-profile-export-domain-mode-conflict");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "sh",
      type: "ps1",
    });
    assert.equal(out.structuredContent.status, "execution_export_mode_conflict");
    assert.equal(out.structuredContent.reasonCode, "execution_export_mode_conflict");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when executionProfile is ambiguous and no selector is provided", async () => {
  const root = createTestTempDir("execution-profile-export-domain-default");
  try {
    writeProject(root);
    writePlanContract(root, "alternate-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "execution_profile_ambiguous");
    assert.equal(out.structuredContent.reasonCode, "execution_profile_ambiguous");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves project ambiguity when projectName is explicitly provided", async () => {
  const root = createTestTempDir("execution-profile-export-domain-project-selector");
  try {
    writeProjectWithName(root, "test-project", "regression-test-run", "gateway-route-smoke-spec");
    writeProjectWithName(root, "test-project-performance", "test-performance-contract-run", "mcp-tool-performance-replay-spec");
    writePlanContract(root, "mcp-tool-performance-replay-spec", "test-project-performance");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName: "test-project-performance",
      executionProfile: "test-performance-contract-run",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-test-performance-contract-run$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports performance replay package for sh mode", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-sh");
  try {
    writePerformanceProject(root);
    writeProbeConfig(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName: "test-performance-project",
      executionProfile: "type-performance-hello-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "sh");
    assert.equal(out.structuredContent.suiteType, "performance");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-type-performance-hello-suite$/);
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    assert.ok(fs.existsSync(path.join(exportDirAbs, "performance-export.bundle.json")));
    assert.ok(fs.existsSync(path.join(exportDirAbs, "run-performance-profile.js")));
    assert.match(String(out.structuredContent.output?.scriptPathAbs ?? ""), /run-performance-profile\.sh$/);
    const scriptText = fs.readFileSync(String(out.structuredContent.output?.scriptPathAbs ?? ""), "utf8");
    assert.match(scriptText, /__MCPJVM_WORKSPACE_ROOT=/);
    assert.match(scriptText, /workspace_root_unresolved/);
    const bundle = readJson(path.join(exportDirAbs, "performance-export.bundle.json"));
    assert.equal(bundle.suiteType, "performance");
    assert.equal(bundle.executionProfile, "type-performance-hello-suite");
    assert.equal(bundle.plans[0].probeBaseUrl, "http://127.0.0.1:9195");
    assert.equal(bundle.plans[0].contract.loadModel.mode, "concurrency");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain preserves explicit performance probe baseUrl when probe registry is unavailable", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-explicit-probe-base-url");
  try {
    writePerformanceProjectWithExplicitProbeBaseUrl(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName: "test-performance-project-explicit-probe",
      executionProfile: "type-performance-review-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.suiteType, "performance");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const bundle = readJson(path.join(exportDirAbs, "performance-export.bundle.json"));
    assert.equal(bundle.plans[0].probeBaseUrl, "http://127.0.0.1:9194");
    assert.equal(bundle.plans[0].contract.observationTargets.baseUrl, "http://127.0.0.1:9194");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports performance replay package for ps1 mode", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-ps1");
  try {
    writePerformanceProject(root);
    writeProbeConfig(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName: "test-performance-project",
      executionProfile: "type-performance-hello-suite",
      mode: "ps1",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "ps1");
    assert.equal(out.structuredContent.suiteType, "performance");
    assert.match(String(out.structuredContent.output?.scriptPathAbs ?? ""), /run-performance-profile\.ps1$/);
    assert.match(String(out.structuredContent.output?.readmePathAbs ?? ""), /README\.performance\.ps1\.md$/);
    const readme = fs.readFileSync(String(out.structuredContent.output?.readmePathAbs ?? ""), "utf8");
    assert.match(readme, /SuiteType: `performance`/);
    assert.match(readme, /ReplayPackageType: `workload_replay_only`/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for performance postman export", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-postman");
  try {
    writePerformanceProject(root);
    writeProbeConfig(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName: "test-performance-project",
      executionProfile: "type-performance-hello-suite",
      mode: "postman",
    });

    assert.equal(out.structuredContent.status, "performance_export_mode_unsupported");
    assert.equal(out.structuredContent.reasonCode, "performance_export_mode_unsupported");
    assert.equal(out.structuredContent.reasonMeta.suiteType, "performance");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports JMeter-compatible performance replay package for sh mode", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-jmeter-sh");
  try {
    const projectName = "test-performance-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "type-performance-jmeter-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-jmeter" }],
            },
          ],
        },
      ],
    });
    writePerformanceJmeterPlanContract(root, projectName, "type-performance-jmeter");
    writeProbeConfig(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-jmeter-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "sh");
    assert.equal(out.structuredContent.suiteType, "performance");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const bundle = readJson(path.join(exportDirAbs, "performance-export.bundle.json"));
    assert.equal(bundle.plans[0].contract.workloadProvider.type, "jmeter");
    assert.equal(bundle.plans[0].contract.workloadProvider.mode, "generated_http");
    assert.equal(
      bundle.plans[0].exportedArtifacts.jmxPathRel,
      "artifacts/jmeter/type-performance-jmeter.workload.jmeter.jmx",
    );
    const jmxPathAbs = path.join(exportDirAbs, "artifacts", "jmeter", "type-performance-jmeter.workload.jmeter.jmx");
    assert.equal(fs.existsSync(jmxPathAbs), true);
    const jmxText = fs.readFileSync(jmxPathAbs, "utf8");
    assert.match(jmxText, /<jmeterTestPlan/);
    assert.match(jmxText, /GET http:\/\/127\.0\.0\.1:8080\/api\/metrics\/hello/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when performance export probe binding cannot be resolved", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-probe-binding-missing");
  try {
    const appRoot = path.join(root, "app-workspace");
    const projectName = "test-performance-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: appRoot,
          executionProfiles: [
            {
              executionProfile: "type-performance-hello-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-hello" }],
            },
          ],
        },
      ],
    });
    writePerformancePlanContract(root, projectName, "type-performance-hello");
    writeJson(path.join(appRoot, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "workspace-default",
      profiles: {
        "workspace-default": {
          probes: {
            "different-probe": {
              baseUrl: "http://127.0.0.1:9195",
            },
          },
        },
      },
      workspaces: [
        {
          root: appRoot,
          profile: "workspace-default",
        },
      ],
    });

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-hello-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "performance_export_probe_binding_missing");
    assert.equal(out.structuredContent.reasonCode, "performance_export_probe_binding_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves performance probe baseUrl from selected project workspace root", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-probe-workspace-root");
  try {
    const appRoot = path.join(root, "app-workspace");
    const projectName = "test-performance-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: appRoot,
          executionProfiles: [
            {
              executionProfile: "type-performance-hello-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-hello" }],
            },
          ],
        },
      ],
    });
    writePerformancePlanContract(root, projectName, "type-performance-hello");
    writeJson(path.join(appRoot, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "workspace-default",
      profiles: {
        "workspace-default": {
          probes: {
            "composite-service": {
              baseUrl: "http://127.0.0.1:9195",
            },
          },
        },
      },
      workspaces: [
        {
          root: appRoot,
          profile: "workspace-default",
        },
      ],
    });

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-hello-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const bundle = readJson(path.join(exportDirAbs, "performance-export.bundle.json"));
    assert.equal(bundle.plans[0].probeBaseUrl, "http://127.0.0.1:9195");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves performance probe baseUrl when probe-config.json has utf8 bom", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-probe-bom");
  try {
    const appRoot = path.join(root, "app-workspace");
    const projectName = "test-performance-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: appRoot,
          executionProfiles: [
            {
              executionProfile: "type-performance-hello-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-hello" }],
            },
          ],
        },
      ],
    });
    writePerformancePlanContract(root, projectName, "type-performance-hello");
    writeJsonWithBom(path.join(appRoot, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "workspace-default",
      profiles: {
        "workspace-default": {
          probes: {
            "composite-service": {
              baseUrl: "http://127.0.0.1:9195",
            },
          },
        },
      },
      workspaces: [
        {
          root: appRoot,
          profile: "workspace-default",
        },
      ],
    });

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-hello-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const bundle = readJson(path.join(exportDirAbs, "performance-export.bundle.json"));
    assert.equal(bundle.plans[0].probeBaseUrl, "http://127.0.0.1:9195");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports JMeter-compatible performance replay package for ps1 mode", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-jmeter-ps1");
  try {
    const projectName = "test-performance-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "type-performance-jmeter-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-jmeter" }],
            },
          ],
        },
      ],
    });
    writePerformanceJmeterPlanContract(root, projectName, "type-performance-jmeter");
    writeProbeConfig(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-jmeter-suite",
      mode: "ps1",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "ps1");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const jmxPathAbs = path.join(exportDirAbs, "artifacts", "jmeter", "type-performance-jmeter.workload.jmeter.jmx");
    assert.equal(fs.existsSync(jmxPathAbs), true);
    assert.match(String(out.structuredContent.output?.scriptPathAbs ?? ""), /run-performance-profile\.ps1$/);
    const readme = fs.readFileSync(String(out.structuredContent.output?.readmePathAbs ?? ""), "utf8");
    assert.match(readme, /## JMeter Artifacts/);
    assert.match(readme, /artifacts\/jmeter\/type-performance-jmeter\.workload\.jmeter\.jmx/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exported performance replay runner executes JMeter-backed package end-to-end", async () => {
  const root = createTestTempDir("execution-profile-export-domain-performance-jmeter-runner");
  const http = require("node:http");
  const startServer = async (handler: (req: any, res: any) => void) => {
    const server = http.createServer(handler);
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("server_address_unavailable");
    }
    return {
      server,
      port: address.port,
      close: async () => {
        await new Promise<void>((resolve, reject) => server.close((error: Error | undefined) => (error ? reject(error) : resolve())));
      },
    };
  };
  let appServer: { close: () => Promise<void>; port: number } | null = null;
  let probeServer: { close: () => Promise<void>; port: number } | null = null;
  try {
    const projectName = "test-performance-project";
    const fakeJmeterHome = writeFakeJmeterInstallation(root);
    appServer = await startServer((req: any, res: any) => {
      if (req.url === "/actuator/health") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end('{"status":"UP"}');
        return;
      }
      res.writeHead(200, { "content-type": "text/plain" });
      res.end("ok");
    });
    probeServer = await startServer((req: any, res: any) => {
      if (req.url === "/__probe/reset" && req.method === "POST") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end('{"result":{"reasonCode":"ok"}}');
        return;
      }
      if (typeof req.url === "string" && req.url.startsWith("/__probe/status")) {
        res.writeHead(200, { "content-type": "application/json" });
        res.end('{"probe":{"hitCount":1}}');
        return;
      }
      res.writeHead(404, { "content-type": "application/json" });
      res.end('{"error":"not_found"}');
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "type-performance-jmeter-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-jmeter" }],
            },
          ],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", "type-performance-jmeter", "metadata.json"), {
      suiteType: "performance",
      execution: { intent: "performance" },
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", "type-performance-jmeter", "contract.json"), {
      entrypoints: [
        {
          transport: {
            protocol: "http",
            baseUrl: `http://127.0.0.1:${appServer.port}`,
            healthCheckPath: "/actuator/health",
            wrappedOnly: true,
          },
          request: {
            method: "GET",
            path: "/api/metrics/hello",
          },
        },
      ],
      workloadProvider: {
        type: "jmeter",
        mode: "generated_http",
        options: {
          installationPath: fakeJmeterHome,
        },
      },
      observationTargets: {
        baseUrl: `http://127.0.0.1:${probeServer.port}`,
        requiredLineHits: ["io.example.MetricsController#hello:52"],
      },
      loadModel: {
        mode: "concurrency",
        concurrency: 10,
        rampUpSeconds: 2,
        durationSeconds: 30,
      },
      successCriteria: {
        maxErrorRatePct: 1,
        minThroughputPerSec: 0.05,
        p95LatencyMs: 1200,
      },
    });

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-jmeter-suite",
      mode: "sh",
    });

    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const bundlePathAbs = path.join(exportDirAbs, "performance-export.bundle.json");
    const envFilePathAbs = path.join(exportDirAbs, "project.env");
    const runnerPathAbs = path.join(exportDirAbs, "run-performance-profile.js");
    const result = await new Promise<{ status: number | null; stdout: string; stderr: string }>((resolve) => {
      const child = require("node:child_process").spawn(
        process.execPath,
        [
          runnerPathAbs,
          "--bundle",
          bundlePathAbs,
          "--env-file",
          envFilePathAbs,
          "--export-dir",
          exportDirAbs,
        ],
        {
          cwd: exportDirAbs,
          env: {
            ...process.env,
            MCP_JAVA_DEV_TOOLS_JMETER_HOME: fakeJmeterHome,
          },
          stdio: ["ignore", "pipe", "pipe"],
          windowsHide: true,
        },
      );
      let stdout = "";
      let stderr = "";
      child.stdout.on("data", (chunk: Buffer) => {
        stdout += chunk.toString();
      });
      child.stderr.on("data", (chunk: Buffer) => {
        stderr += chunk.toString();
      });
      child.on("close", (code: number | null) => {
        resolve({ status: code, stdout, stderr });
      });
    });

    assert.equal(result.status, 0, result.stderr || result.stdout);
    const summary = JSON.parse(String(result.stdout).trim());
    assert.equal(summary.status, "pass");
    const runsRootAbs = path.join(exportDirAbs, "runs");
    const runIds = fs.readdirSync(runsRootAbs);
    assert.equal(runIds.length, 1);
    const runDirAbs = path.join(runsRootAbs, runIds[0]);
    const planResult = readJson(path.join(runDirAbs, "type-performance-jmeter.execution.result.json"));
    assert.equal(planResult.runStatus, "pass");
    assert.equal(planResult.metrics.totalRequests, 3);
    assert.equal(planResult.workloadProvider.type, "jmeter");
    assert.equal(fs.existsSync(planResult.workloadProviderArtifacts.jmxPathAbs), true);
    assert.equal(fs.existsSync(planResult.workloadProviderArtifacts.jtlPathAbs), true);
    assert.equal(fs.existsSync(planResult.workloadProviderArtifacts.logPathAbs), true);
  } finally {
    if (appServer) {
      await appServer.close();
    }
    if (probeServer) {
      await probeServer.close();
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves containing execution profile by planName selector", async () => {
  const root = createTestTempDir("execution-profile-export-domain-plan-selector");
  try {
    writeProject(root);
    writePlanContract(root, "alternate-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      mode: "sh",
      planName: "alternate-spec",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-alternate-run$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports postman collection when scripts are JS-compatible", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(
      path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"),
      "pm.environment.set('token', 'x');\n",
      "utf8",
    );

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "postman");
    assert.match(String(out.structuredContent.output?.collectionPathAbs ?? ""), /collection\.postman\.json$/);
    assert.match(String(out.structuredContent.output?.environmentPathAbs ?? ""), /environment\.postman\.json$/);
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    assert.equal(collection.info.schema, "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
    assert.equal(environment._postman_variable_scope, "environment");
    assert.equal(typeof environment._postman_exported_at, "undefined");
    assert.equal(typeof collection.item[0].request.url, "string");

    const out2 = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    const collection2 = readJson(String(out2.structuredContent.output?.collectionPathAbs));
    const environment2 = readJson(String(out2.structuredContent.output?.environmentPathAbs));
    assert.equal(collection2.info.schema, collection.info.schema);
    assert.match(String(collection2.info.name ?? ""), /^execution-profile:regression-test-run:\d{8}-\d{6}-regression-test-run$/);
    assert.match(String(collection.info.name ?? ""), /^execution-profile:regression-test-run:\d{8}-\d{6}-regression-test-run$/);
    assert.deepEqual({ ...collection2, info: { ...collection2.info, name: "<normalized>" } }, { ...collection, info: { ...collection.info, name: "<normalized>" } });
    assert.deepEqual(environment2, environment);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports postman collection with plan folders for multi-plan profiles", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-plan-folders");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-js", phase: "prePlan" }],
              plans: [
                { order: 1, planName: "plan-a" },
                { order: 2, planName: "plan-b" },
              ],
            },
          ],
        },
      ],
    });
    writePlanArtifact(root, "plan-a", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [{ order: 1, id: "a1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/a" } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    writePlanArtifact(root, "plan-b", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.B", method: "m" } }],
      prerequisites: [],
      steps: [{ order: 1, id: "b1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/b" } }, expect: [{ id: "e2", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    assert.equal(Array.isArray(collection.item), true);
    assert.equal(collection.item.length, 2);
    assert.equal(collection.item[0].name, "[1] plan-a");
    assert.equal(collection.item[1].name, "[2] plan-b");
    assert.equal(Array.isArray(collection.item[0].item), true);
    assert.equal(Array.isArray(collection.item[1].item), true);
    assert.equal(collection.item[0].item[0].request.url, "http://127.0.0.1:8080/a");
    assert.equal(collection.item[1].item[0].request.url, "http://127.0.0.1:8080/b");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain normalizes ${var} syntax and emits referenced environment variables", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-vars");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "POST",
              url: "http://127.0.0.1:9001/api/courses",
              headers: { Authorization: "Bearer ${auth.bearer}" },
              body: { title: "${courseTitle}" },
            },
          },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    assert.equal(collection.item[0].request.url, "http://127.0.0.1:9001/api/courses");
    assert.equal(collection.item[0].request.header[0].value, "Bearer {{auth.bearer}}");
    assert.match(collection.item[0].request.body.raw, /"title": "\{\{courseTitle\}\}"/);
    const envKeys = environment.values.map((entry: any) => entry.key).sort();
    assert.deepEqual(envKeys, ["auth.bearer", "courseTitle"]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when url authority variable has no default", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-url-default-missing");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: "${apiBaseUrl}/api/courses" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.cause, "url_variable_default_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain applies prerequisite defaults and uses gatewayBaseUrl variable as URL authority", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-gateway-default");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "gatewayBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://service-gateway" }],
      steps: [
        {
          order: 1,
          id: "route_courses_via_gateway",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "${gatewayBaseUrl}/courses" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    assert.equal(collection.item[0].request.url, "{{gatewayBaseUrl}}/courses");
    const gatewayVar = environment.values.find((entry: any) => entry.key === "gatewayBaseUrl");
    assert.equal(gatewayVar.value, "http://service-gateway");
    const authorVar = environment.values.find((entry: any) => entry.key === "courseAuthor");
    if (authorVar) {
      assert.equal(authorVar.type, "default");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves auth.bearer from workspace env when includeResolvedSecrets=true", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-auth-resolved");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".env",
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [{ executionProfile: "regression-test-run", executionPolicy: "stop_on_fail", scriptRefs: [{ name: "setup-js", phase: "prePlan" }], plans: [{ order: 1, planName: "gateway-route-smoke-spec" }] }],
        },
      ],
    });
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.writeFileSync(path.join(root, ".env"), "AUTH_BEARER_TOKEN=secret-token\n", "utf8");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
      includeResolvedSecrets: true,
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "secret-token");
    assert.equal(authVar.type, "secret");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain does not resolve auth.bearer from sessionExport includeResolvedSecrets without explicit request opt-in", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-auth-session-default");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".env",
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          sessionExport: { includeResolvedSecrets: true },
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [{ executionProfile: "regression-test-run", executionPolicy: "stop_on_fail", scriptRefs: [{ name: "setup-js", phase: "prePlan" }], plans: [{ order: 1, planName: "gateway-route-smoke-spec" }] }],
        },
      ],
    });
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.writeFileSync(path.join(root, ".env"), "AUTH_BEARER_TOKEN=session-default-token\n", "utf8");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.cause, "required_prerequisite_unresolved");
    assert.equal(out.structuredContent.reasonMeta.prerequisiteKey, "auth.bearer");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves required auth.bearer via contextBindings env key mapping", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-binding-map");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".env",
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [{ executionProfile: "regression-test-run", executionPolicy: "stop_on_fail", scriptRefs: [{ name: "setup-js", phase: "prePlan" }], plans: [{ order: 1, planName: "gateway-route-smoke-spec" }] }],
        },
      ],
    });
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.writeFileSync(path.join(root, ".env"), "TOKEN_KEY=bind-token\n", "utf8");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
      contextBindings: { "auth.bearer": "TOKEN_KEY" },
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "bind-token");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when required prerequisite remains unresolved", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-required-unresolved");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.cause, "required_prerequisite_unresolved");
    assert.equal(out.structuredContent.reasonMeta.prerequisiteKey, "auth.bearer");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves required prerequisite from contextValues without env", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-context-values");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
      contextValues: { "auth.bearer": "ctx-token" },
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "ctx-token");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain supports derived required prerequisite via postman extract before downstream use", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-derived-context");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "courseId", required: true, secret: false, provisioning: "user_input" }],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: "http://127.0.0.1:9001/api/courses", body: { title: "x" } } },
          extract: [{ from: "response.bodyJson.courseId", as: "courseId" }],
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
        {
          order: 2,
          id: "use_course_id",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://127.0.0.1:8080/api/course/{{courseId}}" } },
          expect: [{ id: "e2", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    assert.equal(collection.item[1].request.url, "http://127.0.0.1:8080/api/course/{{courseId}}");
    assert.equal(collection.item[0].event?.[0]?.listen, "test");
    const execLines = collection.item[0].event?.[0]?.script?.exec ?? [];
    assert.ok(execLines.some((line: string) => line.includes("pm.environment.set(\"courseId\"")));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain executes non-JS prerequisite script before postman export", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-blocked");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "setup-ps1",
              phase: "prePlan",
              command: "powershell",
              args: ["-File", ".mcpjvm/test-project/scripts/setup.ps1"],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-ps1", phase: "prePlan" }],
              plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
            },
          ],
        },
      ],
    });
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.ps1"), "Write-Output 'x'\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "postman");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when JS script is not Postman-compatible", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-js-format");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const token = 'x';\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_script_invalid_format");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_script_validation");
    assert.equal(out.structuredContent.reasonMeta.scriptName, "setup-js");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when script implies provisioning", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-provisioning");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "setup-provision",
              phase: "preRuntime",
              command: "docker",
              args: ["compose", "up", "-d"],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-provision", phase: "preRuntime" }],
              plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
            },
          ],
        },
      ],
    });
    writePlanContract(root, "gateway-route-smoke-spec");
    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_provisioning_not_supported");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_scope_guard");
    assert.equal(out.structuredContent.reasonMeta.scriptName, "setup-provision");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when plan step transport is non-http", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-transport");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "probe", transport: {} }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_export_render");
    assert.equal(out.structuredContent.reasonMeta.cause, "unsupported_transport");
    assert.equal(out.structuredContent.reasonMeta.planName, "gateway-route-smoke-spec");
    assert.equal(out.structuredContent.reasonMeta.stepId, "s1");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when url is unresolved", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-url");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "s1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_export_render");
    assert.equal(out.structuredContent.reasonMeta.cause, "url_unresolved");
    assert.equal(out.structuredContent.reasonMeta.planName, "gateway-route-smoke-spec");
    assert.equal(out.structuredContent.reasonMeta.stepId, "s1");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when url is not runner-runnable", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-unrunnable-url");
  try {
    writeProject(root);
    const projectName = "test-project";
    writePlanArtifact(root, "gateway-route-smoke-spec", {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "s1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "/api/courses" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_export_render");
    assert.equal(out.structuredContent.reasonMeta.cause, "url_unrunnable");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

