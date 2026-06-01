const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { writeExecutionProfileExport } = require("@tools-regression-execution-plan-spec/regression_execution_profile_export_writer.util");
const { exportExecutionProfileSh } = require("@tools-export-execution-profile/index");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("exportExecutionProfileSh writes deterministic script from export manifest", async () => {
  const root = createTestTempDir("execution-profile-sh-export");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".mcpjvm/test-project/.env",
          runPrerequisites: [
            {
              order: 1,
              id: "bootstrap-runtime",
              type: "script",
              onFail: "block",
              script: {
                command: "sh",
                scriptPath: ".mcpjvm/test-project/scripts/bootstrap.sh",
                args: ["--seed"],
              },
            },
          ],
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              startups: [
                {
                  name: "gateway-service",
                  command: "java",
                  args: ["-jar", "gateway.jar"],
                  appdir: "microservices/gateway-service",
                },
              ],
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
    const bootstrapScriptPath = path.join(root, ".mcpjvm", "test-project", "scripts", "bootstrap.sh");
    fs.mkdirSync(path.dirname(bootstrapScriptPath), { recursive: true });
    fs.writeFileSync(bootstrapScriptPath, "#!/usr/bin/env bash\necho BOOTSTRAPPED=true\n", "utf8");

    const written = await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-001",
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

    const out = await exportExecutionProfileSh({
      workspaceRootAbs: root,
      exportId: written.exportId,
      includeResolvedSecrets: false,
    });

    assert.ok(fs.existsSync(out.scriptPathAbs));
    assert.equal(out.readmePathAbs, undefined);

    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /EXECUTION PROFILE EXPORT/);
    assert.match(script, /__MCPJVM_EXPORT_SCRIPT_DIR=/);
    assert.match(script, /__MCPJVM_PROJECT_ENV="\$\{__MCPJVM_EXPORT_SCRIPT_DIR\}\/project\.env"/);
    assert.match(script, /__MCPJVM_WORKSPACE_ROOT=/);
    assert.match(script, /workspace_root_unresolved/);
    assert.match(script, /if \[ -f "\$\{__MCPJVM_PROJECT_ENV\}" \]/);
    assert.equal(fs.existsSync(path.join(path.dirname(out.scriptPathAbs), "project.env")), true);
    assert.match(script, /SECTION B: RUNTIME_STARTUP/);
    assert.match(script, /\[R01\]/);
    assert.match(script, /cd "\$\{__MCPJVM_WORKSPACE_ROOT\}\/microservices\/gateway-service"/);
    assert.match(script, /\[PR\] bootstrap-runtime/);
    assert.match(script, /cat > "\$__MCPJVM_EXPORT_TMP\/01-bootstrap-runtime"/);
    assert.match(script, /bash "\$__MCPJVM_EXPORT_TMP\/01-bootstrap-runtime" "--seed"/);
    assert.match(script, /prerequisite_exported:/);
    assert.match(script, /\[P00\] no required placeholder inputs detected/);
    assert.match(script, /SECTION C: HEALTHCHECK_GATE/);
    assert.match(script, /SECTION C2: AUTH_BOOTSTRAP/);
    assert.match(script, /SECTION C3: PRE_PLAN_SCRIPTS/);
    assert.match(script, /\[A00\] auth bootstrap skipped; no AUTH_BEARER placeholder detected/);
    assert.match(script, /\[H01\]/);
    assert.match(script, /\[E01\] plan-a status=executed/);
    assert.match(script, /\[E02\] plan-b status=executed/);
    assert.match(script, /# RunStatus: pass/);
    assert.doesNotMatch(script, /REPLAY_COMMAND/);
    assert.equal(script.includes("SENSITIVE EXPORT"), false);

    assert.equal(fs.existsSync(path.join(path.dirname(out.scriptPathAbs), "README.sh.md")), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh writes sh export as one-off artifact under exports date-uuid folder", async () => {
  const root = createTestTempDir("execution-profile-sh-export-unique");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "auth-tools",
              mode: "terminal",
              startups: [
                {
                  name: "refresh-keycloak-token",
                  command: "powershell",
                  args: ["-File", "scripts/refresh-keycloak-token.ps1"],
                },
              ],
            },
          ],
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

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-uniq",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const out1 = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-uniq" });
    const out2 = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-uniq" });

    assert.notEqual(path.dirname(out1.scriptPathAbs), path.dirname(out2.scriptPathAbs));
    assert.match(out1.scriptPathAbs, /exports[\\/]\d{4}-\d{2}-\d{2}-[0-9a-f-]+[\\/]run-execution-profile\.sh$/);
    assert.match(out2.scriptPathAbs, /exports[\\/]\d{4}-\d{2}-\d{2}-[0-9a-f-]+[\\/]run-execution-profile\.sh$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh emits docker teardown trap when runtime context opts into autoStopOnFinish", async () => {
  const root = createTestTempDir("execution-profile-sh-teardown");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "docker-cli",
              mode: "docker",
              composeFile: "docker/docker-compose-all.yml",
              autoStopOnFinish: true,
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "docker-cli",
              plans: [{ order: 1, planName: "plan-a" }],
            },
          ],
        },
      ],
    });

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-teardown",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      runtimeContextName: "docker-cli",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-teardown" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /docker compose -f "\$\{__MCPJVM_WORKSPACE_ROOT\}\/docker\/docker-compose-all\.yml" up -d/);
    assert.match(script, /__mcpjvm_runtime_teardown\(\)/);
    assert.match(script, /docker compose -f "\$\{__MCPJVM_WORKSPACE_ROOT\}\/docker\/docker-compose-all\.yml" down/);
    assert.match(script, /trap '__mcpjvm_runtime_teardown; rm -rf "\$\{__MCPJVM_EXPORT_TMP:-\}"' EXIT/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh does not infer API base URL from Docker compose when explicit route context is missing", async () => {
  const root = createTestTempDir("execution-profile-sh-compose-base-url");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "docker-compose-all",
              mode: "docker",
              composeFile: "docker/docker-compose-all.yml",
              autoStart: true,
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "docker-compose-all",
              plans: [{ order: 1, planName: "course-service-regression-spec" }],
            },
          ],
        },
      ],
    });
    fs.mkdirSync(path.join(root, "docker"), { recursive: true });
    fs.writeFileSync(
      path.join(root, "docker", "docker-compose-all.yml"),
      [
        "services:",
        "  course:",
        "    ports:",
        '      - "9001:8080"',
        "  course-composite:",
        "    ports:",
        '      - "8080:8080"',
      ].join("\n"),
      "utf8",
    );

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-compose-base",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      runtimeContextName: "docker-compose-all",
      planRuns: [
        { order: 1, planName: "course-service-regression-spec", status: "executed", runStatus: "pass", runId: "run-a" },
      ],
    });

    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "course-service-regression-spec");
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.Course", method: "post" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/api/courses", body: { title: "Regression Course" } } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-compose-base" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /missing_required_input: API_BASE_URL/);
    assert.match(script, /curl -fsS -X "POST".*"\$\{API_BASE_URL\}\/api\/courses"/);
    assert.doesNotMatch(script, /http:\/\/127\.0\.0\.1:9001\/api\/courses/);
    assert.doesNotMatch(script, /auto_input_defaulted: API_BASE_URL/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh resolves terminal service base URL from probe-config runtime.port", async () => {
  const root = createTestTempDir("execution-profile-sh-probe-config-terminal");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      workspaces: [{ root, profile: "dev" }],
      profiles: {
        dev: {
          defaultProbe: "course-service",
          probes: {
            "course-service": {
              baseUrl: "http://127.0.0.1:9193",
              include: ["x.**"],
              exclude: [],
              runtime: { platform: "spring-boot", port: 9101 },
            },
          },
        },
      },
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              autoStart: true,
              startups: [
                {
                  name: "course-service",
                  command: "mvnw.cmd",
                  args: ["spring-boot:run"],
                  appdir: "microservices/course-service",
                },
              ],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "terminal-cli",
              plans: [{ order: 1, planName: "course-service-regression-spec" }],
            },
          ],
        },
      ],
    });

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-probe-config-terminal",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      runtimeContextName: "terminal-cli",
      planRuns: [
        { order: 1, planName: "course-service-regression-spec", status: "executed", runStatus: "pass", runId: "run-a" },
      ],
    });

    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "course-service-regression-spec");
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.Course", method: "post" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/api/courses", body: { title: "Regression Course" } } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-probe-config-terminal" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /curl -fsS -X "POST".*"http:\/\/127\.0\.0\.1:9101\/api\/courses"/);
    assert.doesNotMatch(script, /\$\{API_BASE_URL\}\/api\/courses/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh uses execution profile plan providedContext apiBaseUrl", async () => {
  const root = createTestTempDir("execution-profile-sh-provided-context");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "terminal-cli",
              plans: [
                {
                  order: 1,
                  planName: "course-service-regression-spec",
                  providedContext: { apiBaseUrl: "http://127.0.0.1:9301" },
                },
              ],
            },
          ],
        },
      ],
    });

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-provided-context",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      runtimeContextName: "terminal-cli",
      planRuns: [
        { order: 1, planName: "course-service-regression-spec", status: "executed", runStatus: "pass", runId: "run-a" },
      ],
    });

    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "course-service-regression-spec");
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.Course", method: "post" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/api/courses", body: { title: "Regression Course" } } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-provided-context" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /http:\/\/127\.0\.0\.1:9301\/api\/courses/);
    assert.doesNotMatch(script, /\$\{API_BASE_URL\}\/api\/courses/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh aligns TARGET_BASE_URL default with resolved plan base URL", async () => {
  const root = createTestTempDir("execution-profile-sh-target-base-url-aligned");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      workspaces: [{ root, profile: "dev" }],
      profiles: {
        dev: {
          probes: {
            "course-composite-service": {
              baseUrl: "http://127.0.0.1:9195",
              include: ["x.**"],
              exclude: [],
              runtime: { port: 8080 },
            },
          },
        },
      },
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "terminal-cli",
              plans: [{ order: 1, planName: "course-composite-service-regression-spec" }],
            },
          ],
        },
      ],
    });

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-target-base-url-aligned",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      runtimeContextName: "terminal-cli",
      planRuns: [
        { order: 1, planName: "course-composite-service-regression-spec", status: "executed", runStatus: "pass", runId: "run-a" },
      ],
    });

    const planRoot = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "course-composite-service-regression-spec",
    );
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.Composite", method: "hello" } }],
      prerequisites: [
        { key: "targetBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://127.0.0.1:9000" },
      ],
      steps: [
        {
          order: 1,
          id: "hello",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "${targetBaseUrl}/api/metrics/hello" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-target-base-url-aligned" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /TARGET_BASE_URL="http:\/\/127\.0\.0\.1:8080"/);
    assert.doesNotMatch(script, /TARGET_BASE_URL="http:\/\/127\.0\.0\.1:9000"/);
    assert.match(script, /curl -fsS -X "GET".*"\$\{TARGET_BASE_URL\}\/api\/metrics\/hello"/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh uses probe-config runtime.port even when Docker compose is present", async () => {
  const root = createTestTempDir("execution-profile-sh-probe-config-precedence");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      workspaces: [{ root, profile: "dev" }],
      profiles: {
        dev: {
          probes: {
            "course-service": {
              baseUrl: "http://127.0.0.1:9193",
              include: ["x.**"],
              exclude: [],
              runtime: { port: 9101 },
            },
          },
        },
      },
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "docker-compose-all",
              mode: "docker",
              composeFile: "docker/docker-compose-all.yml",
              autoStart: true,
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              runtimeContextName: "docker-compose-all",
              plans: [{ order: 1, planName: "course-service-regression-spec" }],
            },
          ],
        },
      ],
    });
    fs.mkdirSync(path.join(root, "docker"), { recursive: true });
    fs.writeFileSync(
      path.join(root, "docker", "docker-compose-all.yml"),
      ["services:", "  course:", "    ports:", '      - "9001:8080"'].join("\n"),
      "utf8",
    );

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-probe-config-precedence",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      runtimeContextName: "docker-compose-all",
      planRuns: [
        { order: 1, planName: "course-service-regression-spec", status: "executed", runStatus: "pass", runId: "run-a" },
      ],
    });

    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "course-service-regression-spec");
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.Course", method: "post" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/api/courses", body: { title: "Regression Course" } } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-probe-config-precedence" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /http:\/\/127\.0\.0\.1:9101\/api\/courses/);
    assert.doesNotMatch(script, /http:\/\/127\.0\.0\.1:9001\/api\/courses/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh emits endpoint-level HTTP commands for executed steps", async () => {
  const root = createTestTempDir("execution-profile-sh-endpoint");
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

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-endpoint",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "plan-a");
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [
        { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
        { key: "gatewayBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082/api" },
        { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
      ],
      steps: [
        {
          order: 1,
          id: "s1",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "GET",
              pathTemplate: "/owners",
              headers: { Authorization: "Bearer ${auth.bearer}" },
            },
          },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
        {
          order: 2,
          id: "s2",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/owners", body: { firstName: "Ana" } } },
          expect: [{ id: "e2", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
        {
          order: 3,
          id: "gateway",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "${gatewayBaseUrl}/courses" } },
          expect: [{ id: "e3", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    writeJson(path.join(planRoot, "runs", "run-a", "context.resolved.json"), {
      apiBaseUrl: "http://127.0.0.1:8080",
    });
    writeJson(path.join(planRoot, "runs", "run-a", "execution.result.json"), {
      status: "pass",
      steps: [
        { order: 1, id: "s1", status: "pass" },
        { order: 2, id: "s2", status: "skipped_condition_false" },
      ],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "session-endpoint" });
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /auto_input_defaulted: API_BASE_URL/);
    assert.match(script, /API_BASE_URL="http:\/\/localhost:8082"/);
    assert.match(script, /missing_required_input: AUTH_BEARER/);
    assert.match(script, /\[A01\] refreshing auth after runtime health gate/);
    assert.match(script, /refresh_auth_bearer\(\)/);
    assert.match(script, /AUTH_BEARER=""/);
    assert.match(script, /endpoint auth refresh failed after retries/);
    assert.match(script, /-H "Authorization: Bearer \$\{AUTH_BEARER\}"/);
    assert.match(script, /curl -fsS -X "GET".*"\$\{API_BASE_URL\}\/owners"/);
    assert.match(script, /GATEWAY_BASE_URL="http:\/\/localhost:8082\/api"/);
    assert.match(script, /curl -fsS -X "GET" "\$\{GATEWAY_BASE_URL\}\/courses"/);
    assert.doesNotMatch(script, /\$\{API_BASE_URL\}\/\$\{GATEWAY_BASE_URL\}/);
    assert.doesNotMatch(script, /powershell/);
    assert.doesNotMatch(script, /then break; fi/);
    assert.match(script, /curl -fsS -X "POST"/);
    assert.match(script, /status=planned/);
    assert.doesNotMatch(script, /\$\{REPLAY_COMMAND\} --plan-name 'plan-a'/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh bundles shared profile scripts and export-local project env", async () => {
  const root = createTestTempDir("execution-profile-sh-bundled-scripts");
  try {
    const projectName = "petclinic-regression";
    const tokenScriptRel = ".mcpjvm/test-project/scripts/refresh-keycloak-token.ps1";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".mcpjvm/test-project/.env",
          variables: {
            bearerTokenEnv: "AUTH_BEARER_TOKEN",
            keycloakClientIdEnv: "KEYCLOAK_CLIENT_ID",
            keycloakClientSecretEnv: "KEYCLOAK_CLIENT_SECRET",
            keycloakUsernameEnv: "KEYCLOAK_USERNAME",
            keycloakPasswordEnv: "KEYCLOAK_PASSWORD",
          },
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
    fs.writeFileSync(
      envFileAbs,
      [
        "AUTH_BEARER_TOKEN=secret-token",
        "KEYCLOAK_CLIENT_ID=course-app",
        "KEYCLOAK_USERNAME=nasruddin",
        "KEYCLOAK_PASSWORD=password",
        "KEYCLOAK_SCOPE=openid",
      ].join("\n"),
      "utf8",
    );
    const tokenScriptAbs = path.join(root, ...tokenScriptRel.split("/"));
    fs.mkdirSync(path.dirname(tokenScriptAbs), { recursive: true });
    fs.writeFileSync(tokenScriptAbs, "param([string]$EnvFile)\nWrite-Output $EnvFile\n", "utf8");

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "session-bundled-scripts",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const out = await exportExecutionProfileSh({
      workspaceRootAbs: root,
      exportId: "session-bundled-scripts",
      includeResolvedSecrets: false,
    });
    const exportDir = path.dirname(out.scriptPathAbs);
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    const projectEnv = fs.readFileSync(path.join(exportDir, "project.env"), "utf8");
    const bundledScriptAbs = path.join(
      exportDir,
      "scripts",
      "keycloak-token-bootstrap",
      "refresh-keycloak-token.ps1",
    );

    assert.equal(fs.existsSync(bundledScriptAbs), true);
    assert.match(script, /SECTION C1: POST_HEALTHCHECK_SCRIPTS/);
    assert.match(script, /\[S01\] postHealthcheck keycloak-token-bootstrap/);
    assert.match(script, /"\$\{__MCPJVM_EXPORT_SCRIPT_DIR\}\/scripts\/keycloak-token-bootstrap\/refresh-keycloak-token\.ps1"/);
    assert.match(script, /"-EnvFile" "\$\{__MCPJVM_PROJECT_ENV\}"/);
    assert.doesNotMatch(script, /\.mcpjvm\/test-project\/scripts\/refresh-keycloak-token\.ps1/);
    assert.match(projectEnv, /AUTH_BEARER_TOKEN=/);
    assert.match(projectEnv, /KEYCLOAK_CLIENT_ID=course-app/);
    assert.match(projectEnv, /KEYCLOAK_PASSWORD=/);
    assert.doesNotMatch(projectEnv, /secret-token/);
    assert.doesNotMatch(projectEnv, /nasruddin/);
    assert.doesNotMatch(projectEnv, /password/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh honors sessionExport includeResolvedSecrets for project env package", async () => {
  const root = createTestTempDir("execution-profile-sh-execution-profile-export-secrets");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".mcpjvm/test-project/.env",
          sessionExport: {
            includeResolvedSecrets: true,
          },
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
    const envFileAbs = path.join(root, ".mcpjvm", "test-project", ".env");
    fs.mkdirSync(path.dirname(envFileAbs), { recursive: true });
    fs.writeFileSync(envFileAbs, "KEYCLOAK_PASSWORD=password\nAUTH_BEARER_TOKEN=secret-token\n", "utf8");

    await writeExecutionProfileExport({
      workspaceRootAbs: root,
      exportId: "execution-profile-export-secrets",
      generatedAt: new Date("2026-05-16T11:00:00.000Z"),
      startedAt: new Date("2026-05-16T10:59:00.000Z"),
      endedAt: new Date("2026-05-16T11:00:00.000Z"),
      executionProfile: "regression-test-run",
      executionPolicy: "stop_on_fail",
      runStatus: "pass",
      planRuns: [{ order: 1, planName: "plan-a", status: "executed", runStatus: "pass", runId: "run-a" }],
    });

    const out = await exportExecutionProfileSh({ workspaceRootAbs: root, exportId: "execution-profile-export-secrets" });
    const exportDir = path.dirname(out.scriptPathAbs);
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    const projectEnv = fs.readFileSync(path.join(exportDir, "project.env"), "utf8");

    assert.match(script, /SENSITIVE EXPORT: includeResolvedSecrets=true/);
    assert.match(projectEnv, /# SENSITIVE EXPORT: includeResolvedSecrets=true\./);
    assert.match(projectEnv, /KEYCLOAK_PASSWORD=password/);
    assert.match(projectEnv, /AUTH_BEARER_TOKEN=secret-token/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
