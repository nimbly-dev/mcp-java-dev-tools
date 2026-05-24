const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { writeExecutionProfileExport } = require("@tools-regression-execution-plan-spec/regression_execution_profile_export_writer.util");
const { exportExecutionProfilePs1 } = require("@tools-export-execution-profile/index");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("exportExecutionProfilePs1 writes deterministic script and readme from export manifest", async () => {
  const root = createTestTempDir("execution-profile-ps1-export");
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
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "terminal-cli",
              plans: [
                { order: 1, planName: "plan-a" },
                { order: 2, planName: "plan-b" },
              ],
            },
          ],
        },
      ],
    });

    const written = await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "Export-001",
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

    const out = await exportExecutionProfilePs1({
      workspaceRootAbs: root,
      exportId: written.exportId,
      includeResolvedSecrets: false,
    });

    assert.ok(fs.existsSync(out.scriptPathAbs));
    assert.ok(fs.existsSync(out.readmePathAbs));

    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /EXECUTION PROFILE EXPORT/);
    assert.match(script, /SECTION B: RUNTIME_STARTUP/);
    assert.match(script, /\[R01\]/);
    assert.match(script, /SECTION C: HEALTHCHECK_GATE/);
    assert.match(script, /\[H01\]/);
    assert.match(script, /\$__health_result = Test-NetConnection/);
    assert.match(script, /\$__health_result\.TcpTestSucceeded -eq \$true/);
    assert.doesNotMatch(script, /Test-NetConnection[^\n]+\| Out-Null\nif \(\$LASTEXITCODE -ne 0\) \{ throw 'healthcheck gate failed' \}/);
    assert.match(script, /\[E01\] plan-a status=executed/);
    assert.match(script, /\[E02\] plan-b status=executed/);
    assert.match(script, /# RunStatus: pass/);
    assert.match(script, /\$script:McpJvmProjectEnv = Join-Path \$script:McpJvmExportScriptDir 'project.env'/);
    assert.match(script, /workspace_root_unresolved/);
    assert.doesNotMatch(script, /\$ReplayCommand = ''/);
    assert.doesNotMatch(script, /Set \$ReplayCommand before executing replay steps/);
    assert.equal(script.includes("SENSITIVE EXPORT"), false);

    const readme = fs.readFileSync(out.readmePathAbs, "utf8");
    assert.match(readme, /ExecutionProfile: `regression-test-run`/);
    assert.match(readme, /IncludeRuntimeStartup: `true`/);
    assert.match(readme, /IncludeHealthcheckGate: `true`/);
    assert.match(readme, /1\. \[1\] plan-a \(executed\)/);
    assert.match(readme, /1\. \[2\] plan-b \(executed\)/);
    assert.doesNotMatch(readme, /\(executed\)1\./);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfilePs1 emits native PowerShell HTTP requests without curl JSON mangling", async () => {
  const root = createTestTempDir("execution-profile-ps1-endpoint");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "plan-a" }],
            },
          ],
        },
      ],
    });

    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "plan-a");
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [
        { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
        { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
      ],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "POST",
              pathTemplate: "/api/courses",
              headers: { Authorization: "Bearer ${auth.bearer}" },
              body: { title: "${course.title}-${run.id}", author: "${course.author}", price: 29.99 },
            },
          },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "Export-ps1-endpoint",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const out = await exportExecutionProfilePs1({ workspaceRootAbs: root, exportId: "Export-ps1-endpoint" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");

    assert.match(script, /Invoke-WebRequest @__step_request/);
    assert.match(script, /\$__step_body = @"/);
    assert.match(script, /"title":"\$\{env:COURSE_TITLE\}-\$\{env:RUN_ID\}"/);
    assert.match(script, /\$__step_headers\['Authorization'\] = "Bearer \$\{env:AUTH_BEARER\}"/);
    assert.doesNotMatch(script, /curl\.exe -fsS/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfilePs1 includes sensitive warning when includeResolvedSecrets=true", async () => {
  const root = createTestTempDir("execution-profile-ps1-export-sensitive");
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
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "continue_on_fail",
              plans: [{ order: 1, planName: "plan-a" }],
            },
          ],
        },
      ],
    });

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "Export-002",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "continue_on_fail",
      runStatus: "partial_fail",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "fail", runId: "run-a" }],
    });

    const out = await exportExecutionProfilePs1({
      workspaceRootAbs: root,
      exportId: "Export-002",
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

test("exportExecutionProfilePs1 bundles shared profile scripts and export-local project env", async () => {
  const root = createTestTempDir("execution-profile-ps1-bundled-scripts");
  try {
    const projectName = "petclinic-regression";
    const tokenScriptRel = ".mcpjvm/test-project/scripts/refresh-keycloak-token.ps1";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".mcpjvm/test-project/.env",
          sessionExport: { includeResolvedSecrets: true },
          scripts: [
            {
              name: "keycloak-token-bootstrap",
              phase: "postHealthcheck",
              command: "powershell",
              args: ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", tokenScriptRel],
              appdir: ".",
              envFileArg: "-EnvFile",
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "keycloak-token-bootstrap", phase: "postHealthcheck" }],
              plans: [{ order: 1, planName: "plan-a" }],
            },
          ],
        },
      ],
    });
    const envFileAbs = path.join(root, ".mcpjvm", "test-project", ".env");
    fs.mkdirSync(path.dirname(envFileAbs), { recursive: true });
    fs.writeFileSync(envFileAbs, "AUTH_BEARER_TOKEN=secret-token\nKEYCLOAK_PASSWORD=password\n", "utf8");
    const tokenScriptAbs = path.join(root, ...tokenScriptRel.split("/"));
    fs.mkdirSync(path.dirname(tokenScriptAbs), { recursive: true });
    fs.writeFileSync(tokenScriptAbs, "param([string]$EnvFile)\nWrite-Output $EnvFile\n", "utf8");

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "Export-ps1-bundled-scripts",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const out = await exportExecutionProfilePs1({ workspaceRootAbs: root, exportId: "Export-ps1-bundled-scripts" });
    const exportDir = path.dirname(out.scriptPathAbs);
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    const projectEnv = fs.readFileSync(path.join(exportDir, "project.env"), "utf8");
    const bundledScriptAbs = path.join(exportDir, "scripts", "keycloak-token-bootstrap", "refresh-keycloak-token.ps1");

    assert.equal(fs.existsSync(bundledScriptAbs), true);
    assert.match(script, /SECTION C1: POST_HEALTHCHECK_SCRIPTS/);
    assert.match(script, /\[S01\] postHealthcheck keycloak-token-bootstrap/);
    assert.match(script, /Join-Path \$script:McpJvmExportScriptDir 'scripts\\keycloak-token-bootstrap\\refresh-keycloak-token\.ps1'/);
    assert.match(script, /'-EnvFile' \(\$script:McpJvmProjectEnv\)/);
    assert.doesNotMatch(script, /\$ReplayCommand/);
    assert.match(projectEnv, /# SENSITIVE EXPORT: includeResolvedSecrets=true\./);
    assert.match(projectEnv, /AUTH_BEARER_TOKEN=secret-token/);
    assert.match(projectEnv, /KEYCLOAK_PASSWORD=password/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
