const assert = require("node:assert/strict");
const childProcess = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { dispatchExecutionProfileExportAction: executionProfileExportDomain } = require("@tools-export-execution-profile");

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

function readJson(filePath: string): any {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function runNodeScript(args: string[], env?: Record<string, string>): { status: number | null; stdout: string; stderr: string } {
  const result = childProcess.spawnSync(process.execPath, args, {
    cwd: process.cwd(),
    encoding: "utf8",
    env: {
      ...process.env,
      ...(env ?? {}),
    },
  });
  return {
    status: result.status,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
  };
}

test("generated performance replay runner persists failedStep and reasonMeta on healthcheck transport failure", async () => {
  const root = createTestTempDir("performance-export-runner-healthcheck-failure");
  try {
    const projectName = "test-performance-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "type-performance-unreachable-suite",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "type-performance-unreachable" }],
            },
          ],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", "type-performance-unreachable", "metadata.json"), {
      suiteType: "performance",
      execution: { intent: "performance" },
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "performance", "type-performance-unreachable", "contract.json"), {
      entrypoints: [
        {
          transport: {
            protocol: "http",
            baseUrl: "http://127.0.0.1:1",
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
        baseUrl: "http://127.0.0.1:9195",
        requiredLineHits: ["io.example.MetricsController#hello:52"],
      },
      loadModel: {
        mode: "concurrency",
        concurrency: 1,
        rampUpSeconds: 0,
        durationSeconds: 1,
      },
      successCriteria: {
        maxErrorRatePct: 1,
        minThroughputPerSec: 1,
        p95LatencyMs: 1000,
      },
    });

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "type-performance-unreachable-suite",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    const runnerPathAbs = path.join(exportDirAbs, "run-performance-profile.js");
    const bundlePathAbs = path.join(exportDirAbs, "performance-export.bundle.json");
    const envFilePathAbs = path.join(exportDirAbs, "project.env");
    const runResult = runNodeScript([runnerPathAbs, "--bundle", bundlePathAbs, "--env-file", envFilePathAbs, "--export-dir", exportDirAbs]);
    assert.equal(runResult.status, 0);

    const runsRootAbs = path.join(exportDirAbs, "runs");
    const runDirs = fs.readdirSync(runsRootAbs).sort();
    assert.ok(runDirs.length > 0);
    const latestRunDirAbs = path.join(runsRootAbs, runDirs[runDirs.length - 1]);
    const planResult = readJson(path.join(latestRunDirAbs, "type-performance-unreachable.execution.result.json"));
    const summary = readJson(path.join(latestRunDirAbs, "execution_orchestration.result.json"));

    assert.equal(planResult.status, "blocked");
    assert.equal(planResult.runStatus, "blocked");
    assert.equal(planResult.reasonCode, "performance_healthcheck_transport_failed");
    assert.equal(planResult.failedStep, "healthcheck");
    assert.equal(planResult.reasonMeta.healthCheckPath, "/actuator/health");
    assert.equal(planResult.reasonMeta.baseUrl, "http://127.0.0.1:1");
    assert.equal(planResult.reasonMeta.planName, "type-performance-unreachable");

    assert.equal(summary.status, "blocked");
    assert.equal(summary.planRuns[0].blockedReasonCode, "performance_healthcheck_transport_failed");
    assert.equal(summary.planRuns[0].failedStep, "healthcheck");
    assert.equal(summary.planRuns[0].reasonMeta.baseUrl, "http://127.0.0.1:1");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
