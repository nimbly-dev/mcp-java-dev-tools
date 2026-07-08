const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const {
  resolveProjectContextForRegression,
} = require("@tools-regression-execution-plan-spec/suite_project_context.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  const nextPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            if (!workspace || typeof workspace !== "object" || Array.isArray(workspace)) return workspace;
            const defaults =
              "defaults" in workspace && workspace.defaults && typeof workspace.defaults === "object"
                ? workspace.defaults
                : {};
            const orchestrator =
              "orchestrator" in defaults && defaults.orchestrator && typeof defaults.orchestrator === "object"
                ? defaults.orchestrator
                : {
                    resumePollMax: 30,
                    resumePollIntervalMs: 10_000,
                    resumePollTimeoutMs: 300_000,
                  };
            return {
              ...workspace,
              defaults: {
                ...defaults,
                orchestrator,
              },
            };
          }),
        }
      : payload;
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(nextPayload, null, 2)}\n`, "utf8");
}

test("resolveProjectContextForRegression fails closed when artifact is missing", async () => {
  const root = createTestTempDir("project-context-missing");
  try {
    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: path.join(root, ".mcpjvm", "my-project", "projects.json"),
      healthChecksEnabled: false,
    });
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") assert.equal(out.reasonCode, "project_artifact_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression resolves auth.bearer from env key reference", async () => {
  const root = createTestTempDir("project-context-auth");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          variables: {
            bearerTokenEnv: "AUTH_BEARER_TOKEN",
          },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      env: { AUTH_BEARER_TOKEN: "runtime-token-value" },
      healthChecksEnabled: false,
    });

    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.contextPatch["auth.bearer"], "runtime-token-value");
      assert.deepEqual(out.secretContextKeys, ["auth.bearer"]);
      assert.equal(out.runtimeContextName, "terminal-cli");
      assert.equal(out.contextPatch["runtime.context.mode"], "terminal");
      assert.equal(out.contextPatch["runtime.autoStart"], false);
      assert.equal(out.contextPatch["runtime.autoStopOnFinish"], true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression resolves generic contextBindings from env key references", async () => {
  const root = createTestTempDir("project-context-bindings");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          variables: {
            contextBindings: {
              apiBaseUrl: "BASE_URL",
              tenantId: "TENANT_ID",
            },
          },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      env: {
        BASE_URL: "http://127.0.0.1:8080",
        TENANT_ID: "tenant-social-001",
      },
      healthChecksEnabled: false,
    });

    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.contextPatch.apiBaseUrl, "http://127.0.0.1:8080");
      assert.equal(out.contextPatch.tenantId, "tenant-social-001");
      assert.deepEqual(out.secretContextKeys, ["apiBaseUrl", "tenantId"]);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression prefers terminal runtime context when no runtimeContextName is provided", async () => {
  const root = createTestTempDir("project-context-runtime-default");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            { name: "docker-compose", mode: "docker", composeFile: "docker-compose.yml" },
            { name: "terminal-cli", mode: "terminal", autoStart: false },
          ],
        },
      ],
    });
    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      healthChecksEnabled: false,
    });
    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.runtimeContextName, "terminal-cli");
      assert.equal(out.contextPatch["runtime.context.mode"], "terminal");
      assert.equal(out.contextPatch["runtime.autoStart"], false);
      assert.equal(out.contextPatch["runtime.autoStopOnFinish"], true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression honors explicit autoStopOnFinish=false", async () => {
  const root = createTestTempDir("project-context-runtime-cleanup-override");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              autoStart: true,
              autoStopOnFinish: false,
              startups: [{ name: "customers-service", command: "java" }],
            },
          ],
        },
      ],
    });
    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      healthChecksEnabled: false,
    });
    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.contextPatch["runtime.autoStopOnFinish"], false);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression fails closed when env key value is missing", async () => {
  const root = createTestTempDir("project-context-env-missing");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          variables: {
            bearerTokenEnv: "AUTH_BEARER_TOKEN",
          },
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      env: {},
      healthChecksEnabled: false,
    });
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") assert.equal(out.reasonCode, "env_key_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression uses workspace defaults retryMax/requestTimeoutMs for health checks", async () => {
  const root = createTestTempDir("project-context-health-retry");
  let attempts = 0;
  const server = http.createServer((_req: any, res: any) => {
    attempts += 1;
    if (attempts === 1) {
      res.statusCode = 503;
      res.end("unavailable");
      return;
    }
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const port = address.port;
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          defaults: { retryMax: 2, requestTimeoutMs: 500 },
          externalSystems: [
            {
              name: "keycloak",
              kind: "identity",
              host: "127.0.0.1",
              port,
              healthChecks: [
                {
                  id: "ready",
                  type: "http",
                  url: `http://127.0.0.1:${port}/health`,
                  required: true,
                },
              ],
            },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      env: {},
      healthChecksEnabled: true,
    });
    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.contextPatch["runtime.requestTimeoutMs"], 500);
      assert.equal(out.contextPatch["runtime.retryMax"], 2);
      assert.equal(out.contextPatch["runtime.orchestrator.resumePollMax"], 30);
      assert.equal(out.contextPatch["runtime.orchestrator.resumePollIntervalMs"], 10_000);
      assert.equal(out.contextPatch["runtime.orchestrator.resumePollTimeoutMs"], 300_000);
    }
    assert.equal(attempts, 2);
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression fails closed when orchestrator defaults are omitted from projects.json", async () => {
  const root = createTestTempDir("project-context-orchestrator-missing");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    fs.mkdirSync(path.dirname(projects), { recursive: true });
    fs.writeFileSync(
      projects,
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      healthChecksEnabled: false,
    });

    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "project_artifact_invalid");
      assert.equal(out.requiredUserAction.some((entry: string) => entry.includes("defaults.orchestrator is required")), true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression returns minimal checks payload when health is unreachable", async () => {
  const root = createTestTempDir("project-context-health-fail");
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          defaults: { retryMax: 1, requestTimeoutMs: 100 },
          externalSystems: [
            {
              name: "postgres",
              kind: "database",
              host: "127.0.0.1",
              port: 1,
              healthChecks: [
                {
                  id: "tcp-open",
                  type: "tcp",
                  target: "127.0.0.1:1",
                  required: true,
                },
              ],
            },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      env: {},
      healthChecksEnabled: true,
    });
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "external_healthcheck_failed");
      assert.deepEqual(out.checks, ["postgres:tcp-open=unreachable"]);
      assert.match(out.nextAction ?? "", /Ensure services are running/);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression does not auto-start when health checks are already ready", async () => {
  const root = createTestTempDir("project-context-autostart-ready");
  const server = http.createServer((_req: any, res: any) => {
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  let starterCalled = 0;
  try {
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const port = address.port;
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              autoStart: true,
              startups: [{ name: "customers-service", command: "java" }],
            },
          ],
          externalSystems: [
            {
              name: "customers-api",
              kind: "service",
              host: "127.0.0.1",
              port,
              healthChecks: [
                { id: "http-ready", type: "http", url: `http://127.0.0.1:${port}/health`, required: true },
              ],
            },
          ],
        },
      ],
    });
    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      runtimeStarter: async () => {
        starterCalled += 1;
        return { attempted: true, success: true };
      },
    });
    assert.equal(out.status, "ok");
    assert.equal(starterCalled, 0);
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression attempts auto-start when health checks fail and autoStart=true", async () => {
  const root = createTestTempDir("project-context-autostart-attempt");
  let checks = 0;
  let starterCalled = 0;
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              autoStart: true,
              startups: [{ name: "customers-service", command: "java" }],
            },
          ],
          defaults: { retryMax: 1, requestTimeoutMs: 50 },
          externalSystems: [
            {
              name: "customers-api",
              kind: "service",
              host: "127.0.0.1",
              port: 1,
              healthChecks: [{ id: "tcp-open", type: "tcp", target: "127.0.0.1:1", required: true }],
            },
          ],
        },
      ],
    });
    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      runtimeStarter: async () => {
        starterCalled += 1;
        return { attempted: true, success: false, detail: "manual terminal start required" };
      },
    });
    checks += 1;
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "external_healthcheck_failed");
      assert.equal(out.checks?.some((entry: string) => entry.includes("runtime:auto_start=failed")), true);
    }
    assert.equal(starterCalled, 1);
    assert.equal(checks, 1);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression waits for delayed health convergence after auto-start", async () => {
  const root = createTestTempDir("project-context-autostart-convergence");
  let startedAt = 0;
  const server = http.createServer((_req: any, res: any) => {
    if (startedAt === 0 || Date.now() - startedAt < 1800) {
      res.statusCode = 503;
      res.end("starting");
      return;
    }
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("server address unavailable");
  const port = address.port;
  let starterCalled = 0;
  try {
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          defaults: { retryMax: 1, requestTimeoutMs: 900 },
          runtimeContexts: [
            {
              name: "docker-compose-all",
              mode: "docker",
              autoStart: true,
              composeFile: "docker/docker-compose-all.yml",
            },
          ],
          externalSystems: [
            {
              name: "customers-api",
              kind: "service",
              host: "127.0.0.1",
              port,
              healthChecks: [
                { id: "http-ready", type: "http", url: `http://127.0.0.1:${port}/health`, required: true },
              ],
            },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      runtimeStarter: async () => {
        starterCalled += 1;
        startedAt = Date.now();
        return { attempted: true, success: true, detail: "started delayed runtime" };
      },
    });

    assert.equal(starterCalled, 1);
    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.contextPatch["runtime.autoStartAttempted"], true);
      assert.equal(out.contextPatch["runtime.autoStarted"], true);
    }
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression attempts auto-start when strict probe is unreachable even if health checks are ready", async () => {
  const root = createTestTempDir("project-context-strict-probe-convergence");
  const apiServer = http.createServer((_req: any, res: any) => {
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => apiServer.listen(0, "127.0.0.1", () => resolve()));
  let starterCalled = 0;
  try {
    const address = apiServer.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const port = address.port;
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              autoStart: true,
              startups: [{ name: "visits-service", command: "java" }],
            },
          ],
          externalSystems: [
            {
              name: "visits-api",
              kind: "service",
              host: "127.0.0.1",
              port,
              healthChecks: [
                { id: "http-ready", type: "http", url: `http://127.0.0.1:${port}/health`, required: true },
              ],
            },
          ],
        },
      ],
    });
    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      strictProbeBaseUrls: ["http://127.0.0.1:65534"],
      runtimeStarter: async () => {
        starterCalled += 1;
        return { attempted: true, success: false, detail: "probe wiring missing" };
      },
    });
    assert.equal(starterCalled, 1);
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "external_healthcheck_failed");
      assert.equal(out.checks?.some((entry: string) => entry.includes("probe:http://127.0.0.1:65534=unreachable")), true);
    }
  } finally {
    apiServer.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression derives strict probe targets from startup names when probeVerification is enabled", async () => {
  const root = createTestTempDir("project-context-strict-probe-derived");
  const apiServer = http.createServer((_req: any, res: any) => {
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => apiServer.listen(0, "127.0.0.1", () => resolve()));
  let starterCalled = 0;
  try {
    const address = apiServer.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const port = address.port;
    const projects = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projects, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            {
              name: "terminal-cli",
              mode: "terminal",
              autoStart: true,
              startups: [{ name: "visits-service", command: "java" }],
            },
          ],
          externalSystems: [
            {
              name: "visits-api",
              kind: "service",
              host: "127.0.0.1",
              port,
              healthChecks: [{ id: "http-ready", type: "http", url: `http://127.0.0.1:${port}/health`, required: true }],
            },
          ],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "visits-service": {
              baseUrl: "http://127.0.0.1:65533",
              include: ["org.example.visits.**"],
              exclude: [],
            },
          },
        },
      },
      workspaces: [{ root, profile: "dev" }],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projects,
      strictProbeVerification: true,
      runtimeStarter: async () => {
        starterCalled += 1;
        return { attempted: true, success: false, detail: "probe wiring missing" };
      },
    });

    assert.equal(starterCalled, 1);
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "external_healthcheck_failed");
      assert.equal(out.checks?.some((entry: string) => entry.includes("probe:http://127.0.0.1:65533=unreachable")), true);
    }
  } finally {
    apiServer.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression prefers terminal-cli by name when multiple terminal contexts exist", async () => {
  const root = createTestTempDir("project-context-terminal-name");
  try {
    const projectName = "petclinic-regression";
    const projectsFile = path.join(root, ".mcpjvm", projectName, "projects.json");
    writeJson(projectsFile, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            { name: "terminal-alt", mode: "terminal", autoStart: false },
            { name: "terminal-cli", mode: "terminal", autoStart: false },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projectsFile,
      healthChecksEnabled: false,
    });

    assert.equal(out.status, "ok");
    if (out.status === "ok") {
      assert.equal(out.runtimeContextName, "terminal-cli");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression fails closed when multiple non-terminal runtime contexts exist and none is selected", async () => {
  const root = createTestTempDir("project-context-ambiguous-nonterminal");
  try {
    const projectName = "petclinic-regression";
    const projectsFile = path.join(root, ".mcpjvm", projectName, "projects.json");
    writeJson(projectsFile, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            { name: "docker-compose", mode: "docker", composeFile: "docker-compose.yml", autoStart: false },
            { name: "docker-compose-alt", mode: "docker", composeFile: "docker-compose.alt.yml", autoStart: false },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projectsFile,
      healthChecksEnabled: false,
    });

    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "runtime_context_unknown");
      assert.match(out.nextAction ?? "", /Provide runtimeContextName explicitly/);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression executes ordered runPrerequisites before health checks", async () => {
  const root = createTestTempDir("project-context-run-prereq-order");
  const marker = path.join(root, "marker.txt");
  try {
    const projectName = "petclinic-regression";
    const projectsFile = path.join(root, ".mcpjvm", projectName, "projects.json");
    writeJson(projectsFile, {
      workspaces: [
        {
          projectRoot: root,
          runPrerequisites: [
            {
              order: 1,
              id: "create-marker",
              type: "script",
              onFail: "block",
              script: {
                command: "node",
                scriptPath: "scripts/create-marker.js",
                timeoutMs: 5000,
              },
            },
            {
              order: 2,
              id: "marker-exists",
              type: "assert",
              onFail: "block",
              assert: {
                kind: "file_exists",
                path: "marker.txt",
              },
            },
          ],
          defaults: { retryMax: 1, requestTimeoutMs: 100 },
          externalSystems: [
            {
              name: "dummy",
              kind: "service",
              host: "127.0.0.1",
              port: 1,
              healthChecks: [{ id: "tcp-open", type: "tcp", target: "127.0.0.1:1", required: true }],
            },
          ],
        },
      ],
    });
    const scriptsDir = path.join(root, "scripts");
    fs.mkdirSync(scriptsDir, { recursive: true });
    fs.writeFileSync(
      path.join(scriptsDir, "create-marker.js"),
      `const fs=require("node:fs");fs.writeFileSync(${JSON.stringify(marker)},"ok\\n","utf8");`,
      "utf8",
    );

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projectsFile,
      env: {},
      healthChecksEnabled: true,
    });

    assert.equal(fs.existsSync(marker), true);
    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "external_healthcheck_failed");
      assert.equal(out.checks?.[0]?.startsWith("run_prereq:create-marker=pass"), true);
      assert.equal(out.checks?.[1]?.startsWith("run_prereq:marker-exists=pass"), true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression dedupes health checks covered by runPrerequisites", async () => {
  const root = createTestTempDir("project-context-run-prereq-dedupe");
  const server = http.createServer((_req: any, res: any) => {
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const addr = server.address();
    if (!addr || typeof addr === "string") throw new Error("addr missing");
    const url = `http://127.0.0.1:${addr.port}/health`;
    const projectsFile = path.join(root, ".mcpjvm", "petclinic-regression", "projects.json");
    writeJson(projectsFile, {
      workspaces: [
        {
          projectRoot: root,
          runPrerequisites: [
            {
              order: 1,
              id: "url-ready",
              type: "assert",
              onFail: "block",
              assert: { kind: "url_reachable", url, timeoutMs: 2000 },
            },
          ],
          externalSystems: [
            {
              name: "api",
              kind: "service",
              host: "127.0.0.1",
              port: addr.port,
              healthChecks: [{ id: "ready", type: "http", url, required: true }],
            },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projectsFile,
      healthChecksEnabled: true,
    });

    assert.equal(out.status, "ok");
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression uses workspace requestTimeoutMs for run prerequisite scripts by default", async () => {
  const root = createTestTempDir("project-context-run-prereq-timeout-default");
  try {
    const projectsFile = path.join(root, ".mcpjvm", "petclinic-regression", "projects.json");
    writeJson(projectsFile, {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 50 },
          runPrerequisites: [
            {
              order: 1,
              id: "slow-script",
              type: "script",
              onFail: "block",
              script: {
                command: "node",
                scriptPath: "scripts/slow-script.js",
              },
            },
          ],
        },
      ],
    });
    const scriptsDir = path.join(root, "scripts");
    fs.mkdirSync(scriptsDir, { recursive: true });
    fs.writeFileSync(
      path.join(scriptsDir, "slow-script.js"),
      "setTimeout(() => process.exit(0), 200);",
      "utf8",
    );

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projectsFile,
      env: {},
      healthChecksEnabled: true,
    });

    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "external_healthcheck_failed");
      assert.equal(out.checks?.some((entry: string) => entry.includes("timeout (50ms)")), true);
    }
  } finally {
    await new Promise<void>((resolve) => setTimeout(resolve, 150));
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveProjectContextForRegression surfaces script_execution_failed for profile script failures", async () => {
  const root = createTestTempDir("project-context-profile-script-failure");
  try {
    const projectName = "petclinic-regression";
    const projectsFile = path.join(root, ".mcpjvm", projectName, "projects.json");
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "unused-plan");
    const scriptsDir = path.join(root, "scripts");
    fs.mkdirSync(scriptsDir, { recursive: true });
    fs.writeFileSync(
      path.join(scriptsDir, "fail-script.js"),
      "process.stderr.write('token bootstrap failed\\n'); process.exit(3);",
      "utf8",
    );
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: {
        intent: "regression",
        probeVerification: false,
        pinStrictProbeKey: false,
        discoveryPolicy: "allow_discoverable_prerequisites",
      },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://127.0.0.1/unused" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });
    writeJson(projectsFile, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          scripts: [
            {
              name: "token-bootstrap",
              phase: "postHealthcheck",
              command: "node",
              args: ["scripts/fail-script.js"],
              appdir: ".",
            },
          ],
          executionProfiles: [
            {
              executionProfile: "core-script-failure",
              executionPolicy: "stop_on_fail",
              scriptRefs: ["token-bootstrap"],
              plans: [{ order: 1, planName: "unused-plan" }],
            },
          ],
        },
      ],
    });

    const out = await resolveProjectContextForRegression({
      workspaceRootAbs: root,
      projectsFileAbs: projectsFile,
      executionProfileName: "core-script-failure",
      healthChecksEnabled: false,
    });

    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.reasonCode, "script_execution_failed");
      assert.equal(out.checks?.some((entry: string) => entry.includes("profile_script:postHealthcheck:token-bootstrap=fail")), true);
      assert.match(out.nextAction ?? "", /Fix profile script/);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
