const assert = require("node:assert/strict");
const childProcess = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  dispatchExecutionProfileExportAction: executionProfileExportDomain,
} = require("@tools-export-execution-profile");

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

function runNodeScript(
  args: string[],
  env?: Record<string, string>,
): { status: number | null; stdout: string; stderr: string } {
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

test("[UT][execution-profile-export][export_execution_profile_performance] generated performance replay runner persists failedStep and reasonMeta on healthcheck transport failure", async () => {
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
    writeJson(
      path.join(
        root,
        ".mcpjvm",
        projectName,
        "plans",
        "performance",
        "type-performance-unreachable",
        "metadata.json",
      ),
      {
        suiteType: "performance",
        execution: { intent: "performance" },
      },
    );
    writeJson(
      path.join(
        root,
        ".mcpjvm",
        projectName,
        "plans",
        "performance",
        "type-performance-unreachable",
        "contract.json",
      ),
      {
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
      },
    );

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
    const runResult = runNodeScript([
      runnerPathAbs,
      "--bundle",
      bundlePathAbs,
      "--env-file",
      envFilePathAbs,
      "--export-dir",
      exportDirAbs,
    ]);
    assert.equal(runResult.status, 1);

    const runsRootAbs = path.join(exportDirAbs, "runs");
    const runDirs = fs.readdirSync(runsRootAbs).sort();
    assert.ok(runDirs.length > 0);
    const latestRunDirAbs = path.join(runsRootAbs, runDirs[runDirs.length - 1]);
    const planResult = readJson(
      path.join(latestRunDirAbs, "type-performance-unreachable.execution.result.json"),
    );
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
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});

test("[UT][execution-profile-export][export_execution_profile_performance] dynamic local performance export bundles the Sidecar lifecycle without persisting startup commands", async () => {
  const root = createTestTempDir("performance-export-dynamic-attach");
  const originalHelper = process.env.MCP_JAVA_ATTACH_HELPER_JAR;
  const originalAgent = process.env.MCP_JAVA_AGENT_JAR;
  try {
    const projectName = "test-performance-dynamic-attach";
    const helperJarAbs = path.join(root, "test-helper.jar");
    const agentJarAbs = path.join(root, "test-agent.jar");
    fs.writeFileSync(helperJarAbs, "helper", "utf8");
    fs.writeFileSync(agentJarAbs, "agent", "utf8");
    process.env.MCP_JAVA_ATTACH_HELPER_JAR = helperJarAbs;
    process.env.MCP_JAVA_AGENT_JAR = agentJarAbs;
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "local-app",
              mode: "terminal",
              autoStart: true,
              autoStopOnFinish: true,
              startups: [
                {
                  name: "app",
                  command: "java",
                  args: ["-Xmx256m", "-jar", "services/app.jar", "--server.port=8080"],
                  env: { SECRET_VALUE: "must-not-export" },
                },
              ],
              sidecarLifecycle: {
                activation: "dynamic_attach_local",
                targetStartupName: "app",
                probeId: "local-app-probe",
                verifyProbeAfterAttach: true,
              },
            },
          ],
          executionProfiles: [
            {
              executionProfile: "dynamic-performance-suite",
              suiteType: "performance",
              runtimeContextName: "local-app",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "dynamic-performance" }],
            },
          ],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "default",
      profiles: {
        default: {
          probes: {
            "local-app-probe": {
              baseUrl: "http://127.0.0.1:19190",
              include: ["io.example.app.**"],
              exclude: ["**.config.**"],
            },
          },
        },
      },
    });
    writeJson(
      path.join(
        root,
        ".mcpjvm",
        projectName,
        "plans",
        "performance",
        "dynamic-performance",
        "metadata.json",
      ),
      { suiteType: "performance", execution: { intent: "performance" } },
    );
    writeJson(
      path.join(
        root,
        ".mcpjvm",
        projectName,
        "plans",
        "performance",
        "dynamic-performance",
        "contract.json",
      ),
      {
        entrypoints: [
          {
            transport: { protocol: "http", baseUrl: "http://127.0.0.1:8080", wrappedOnly: true },
            request: { method: "GET", path: "/health" },
          },
        ],
        observationTargets: {
          probeId: "local-app-probe",
          requiredLineHits: ["io.example.App#health:42"],
        },
        loadModel: { mode: "concurrency", concurrency: 1, rampUpSeconds: 0, durationSeconds: 1 },
        successCriteria: { maxErrorRatePct: 1, minThroughputPerSec: 1, p95LatencyMs: 1000 },
      },
    );

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "dynamic-performance-suite",
      mode: "ps1",
    });

    assert.equal(out.structuredContent.status, "ok");
    const exportDirAbs = String(out.structuredContent.exportDirAbs ?? "");
    assert.ok(fs.existsSync(path.join(exportDirAbs, "sidecar", "jvm-attach-helper.jar")));
    assert.ok(fs.existsSync(path.join(exportDirAbs, "sidecar", "sidecar-agent.jar")));
    assert.ok(fs.existsSync(path.join(exportDirAbs, "run-portable-sidecar-lifecycle.js")));
    const config = readJson(path.join(exportDirAbs, "portable-sidecar-attach.config.json"));
    assert.equal(config.probeId, "local-app-probe");
    assert.equal(config.probeHost, "127.0.0.1");
    assert.equal(config.probePort, 19190);
    assert.equal(config.include, "io.example.app.**");
    assert.equal(config.exclude, "**.config.**");
    assert.equal(Object.hasOwn(config, "command"), false);
    assert.equal(Object.hasOwn(config, "env"), false);
    const script = fs.readFileSync(path.join(exportDirAbs, "run-performance-profile.ps1"), "utf8");
    assert.match(script, /run-portable-sidecar-lifecycle\.js/);
    assert.match(script, /cleanup_unverified/);

    const shOut = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName,
      executionProfile: "dynamic-performance-suite",
      mode: "sh",
    });
    assert.equal(shOut.structuredContent.status, "ok");
    const shExportDirAbs = String(shOut.structuredContent.exportDirAbs ?? "");
    assert.ok(fs.existsSync(path.join(shExportDirAbs, "sidecar", "jvm-attach-helper.jar")));
    assert.ok(fs.existsSync(path.join(shExportDirAbs, "sidecar", "sidecar-agent.jar")));
    const shScript = fs.readFileSync(path.join(shExportDirAbs, "run-performance-profile.sh"), "utf8");
    assert.match(shScript, /run-portable-sidecar-lifecycle\.js/);
    assert.match(shScript, /__mcpjvm_dynamic_attach_cleanup/);
  } finally {
    if (typeof originalHelper === "string") process.env.MCP_JAVA_ATTACH_HELPER_JAR = originalHelper;
    else delete process.env.MCP_JAVA_ATTACH_HELPER_JAR;
    if (typeof originalAgent === "string") process.env.MCP_JAVA_AGENT_JAR = originalAgent;
    else delete process.env.MCP_JAVA_AGENT_JAR;
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});
