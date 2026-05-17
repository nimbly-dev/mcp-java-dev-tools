const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { writeRunSessionExport } = require("@tools-regression-execution-plan-spec/regression_run_session_export_writer.util");
const { exportRunSessionPs1 } = require("@tools-export-run-session/index");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("exportRunSessionPs1 writes deterministic script and readme from session manifest", async () => {
  const root = createTestTempDir("run-session-ps1-export");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              startups: [{ name: "gateway-service", command: "java", args: ["-jar", "gateway.jar"] }],
            },
          ],
          externalSystems: [
            {
              name: "gateway",
              kind: "service",
              host: "127.0.0.1",
              port: 8080,
              healthChecks: [
                { id: "tcp-open", type: "tcp", target: "127.0.0.1:8080", required: true },
                { id: "http-ready", type: "http", url: "http://127.0.0.1:8080/actuator/health", required: false },
              ],
            },
          ],
        },
      ],
    });

    const written = await writeRunSessionExport({
      workspaceRootAbs: root,
      sessionId: "session-001",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "fail",
      runtimeContextName: "terminal-cli",
      planRuns: [
        { order: 2, planName: "plan-b", status: "executed", runStatus: "fail", runId: "run-b" },
        { order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" },
      ],
    });

    const out = await exportRunSessionPs1({
      workspaceRootAbs: root,
      sessionId: written.sessionId,
      includeResolvedSecrets: false,
    });

    assert.ok(fs.existsSync(out.scriptPathAbs));
    assert.ok(fs.existsSync(out.readmePathAbs));

    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /RUN SESSION REPLAY EXPORT/);
    assert.match(script, /SECTION B: RUNTIME_STARTUP/);
    assert.match(script, /\[R01\]/);
    assert.match(script, /SECTION C: HEALTHCHECK_GATE/);
    assert.match(script, /\[H01\]/);
    assert.match(script, /\[E01\] plan-a status=executed/);
    assert.match(script, /\[E02\] plan-b status=executed/);
    assert.match(script, /Set \$ReplayCommand before executing replay steps/);
    assert.equal(script.includes("SENSITIVE EXPORT"), false);

    const readme = fs.readFileSync(out.readmePathAbs, "utf8");
    assert.match(readme, /ExecutionProfile: `regression-test-run`/);
    assert.match(readme, /IncludeRuntimeStartup: `true`/);
    assert.match(readme, /IncludeHealthcheckGate: `true`/);
    assert.match(readme, /1\. \[1\] plan-a \(executed\)/);
    assert.match(readme, /1\. \[2\] plan-b \(executed\)/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportRunSessionPs1 includes sensitive warning when includeResolvedSecrets=true", async () => {
  const root = createTestTempDir("run-session-ps1-export-sensitive");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          sessionExport: {
            includeRuntimeStartup: false,
            includeHealthcheckGate: false,
          },
        },
      ],
    });

    await writeRunSessionExport({
      workspaceRootAbs: root,
      sessionId: "session-002",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "continue_on_fail",
      runStatus: "partial_fail",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "fail", runId: "run-a" }],
    });

    const out = await exportRunSessionPs1({
      workspaceRootAbs: root,
      sessionId: "session-002",
      includeResolvedSecrets: true,
    });

    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /SENSITIVE EXPORT/);
    assert.match(script, /runtime startup skipped/i);
    assert.match(script, /healthcheck gate skipped/i);
    const readme = fs.readFileSync(out.readmePathAbs, "utf8");
    assert.match(readme, /SENSITIVE EXPORT/);
    assert.match(readme, /IncludeRuntimeStartup: `false`/);
    assert.match(readme, /IncludeHealthcheckGate: `false`/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
