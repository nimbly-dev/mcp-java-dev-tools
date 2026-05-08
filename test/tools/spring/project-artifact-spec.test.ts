const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  readProjectArtifact,
  validateProjectArtifact,
  writeProjectArtifact,
} = require("@tools-project-artifact-spec/project_artifact.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("validateProjectArtifact accepts minimal valid shape", () => {
  const result = validateProjectArtifact({
    workspaces: [
      {
        projectRoot: "C:\\workspace\\spring",
        envFile: ".env",
        variables: {
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
        },
        runtimeContexts: [
          {
            name: "terminal-cli",
            mode: "terminal",
            autoStart: true,
            autoStopOnFinish: true,
            startups: [
              {
                name: "customers-service",
                command: "java",
                args: ["-jar", "target/app.jar"],
                appdir: ".",
              },
            ],
          },
          { name: "docker-compose", mode: "docker", composeFile: "docker-compose.yml" },
        ],
        externalSystems: [
          {
            name: "postgres",
            kind: "database",
            host: "localhost",
            port: 5432,
            healthChecks: [
              {
                id: "tcp-open",
                type: "tcp",
                target: "localhost:5432",
                required: true,
              },
            ],
          },
        ],
      },
    ],
  });

  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.artifact.workspaces.length, 1);
    assert.equal(result.artifact.workspaces[0].variables?.bearerTokenEnv, "AUTH_BEARER_TOKEN");
  }
});

test("validateProjectArtifact fails closed when legacy auth field is present", () => {
  const result = validateProjectArtifact({
    workspaces: [
      {
        projectRoot: "C:\\workspace\\spring",
        auth: {
          bearerToken: "raw-token-value",
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
        },
      },
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /auth is unsupported/);
  }
});

test("validateProjectArtifact fails closed when runtime context mode is invalid", () => {
  const result = validateProjectArtifact({
      workspaces: [
      {
        projectRoot: "C:\\workspace\\spring",
        runtimeContexts: [{ name: "cluster", mode: "k8s" }],
      },
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.reasonCode, "runtime_context_unknown");
});

test("validateProjectArtifact fails closed when startups entry is provided without command", () => {
  const result = validateProjectArtifact({
    workspaces: [
      {
        projectRoot: "C:\\workspace\\spring",
        runtimeContexts: [
          {
            name: "terminal-cli",
            mode: "terminal",
            startups: [{ name: "customers-service", args: ["-jar", "app.jar"] }],
          },
        ],
      },
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.reasonCode, "runtime_context_unknown");
});

test("write/read project artifact preserves deterministic shape", async () => {
  const root = createTestTempDir("project-artifact");
  try {
    const out = path.join(root, ".mcpjvm", "my-project", "projects.json");
    await writeProjectArtifact(out, {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          externalSystems: [{ name: "keycloak", kind: "auth-server", host: "localhost", port: 8081 }],
        },
      ],
    });

    const read = await readProjectArtifact(out);
    assert.equal(read.ok, true);
    if (read.ok) {
      assert.equal(read.artifact.workspaces[0].projectRoot, root);
      assert.equal(read.artifact.workspaces[0].runtimeContexts?.[0].mode, "terminal");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
