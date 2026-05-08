const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const {
  resolveProjectContextForRegression,
} = require("@tools-regression-execution-plan-spec/regression_project_context.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
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
      assert.equal(out.runtimeContextName, "terminal-cli");
      assert.equal(out.contextPatch["runtime.context.mode"], "terminal");
      assert.equal(out.contextPatch["runtime.autoStart"], false);
      assert.equal(out.contextPatch["runtime.autoStopOnFinish"], true);
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
    assert.equal(attempts, 2);
  } finally {
    server.close();
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
