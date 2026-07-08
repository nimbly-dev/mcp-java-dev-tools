const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  readProjectArtifact,
  validateProjectArtifact,
  writeProjectArtifact,
} = require("@tools-project-artifact-spec/project_artifact.util");

function withRequiredDefaults(workspace: Record<string, unknown>): Record<string, unknown> {
  const defaults =
    workspace.defaults && typeof workspace.defaults === "object"
      ? (workspace.defaults as Record<string, unknown>)
      : {};
  const orchestrator =
    defaults.orchestrator && typeof defaults.orchestrator === "object"
      ? (defaults.orchestrator as Record<string, unknown>)
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
}

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("validateProjectArtifact accepts minimal valid shape", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
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
      }),
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
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        auth: {
          bearerToken: "raw-token-value",
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
        },
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /auth is unsupported/);
  }
});

test("validateProjectArtifact fails closed when workspace variables contain unsupported env mappings", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        variables: {
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
          tenantIdEnv: "TENANT_ID",
          baseUrlEnv: "BASE_URL",
        },
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /variables\.tenantIdEnv is unsupported/);
    assert.match(result.errors.join("\n"), /variables\.baseUrlEnv is unsupported/);
  }
});

test("validateProjectArtifact accepts workspace variables.contextBindings env mappings", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        variables: {
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
          contextBindings: {
            apiBaseUrl: "BASE_URL",
            tenantId: "TENANT_ID",
          },
        },
      }),
    ],
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.artifact.workspaces[0].variables?.contextBindings?.apiBaseUrl, "BASE_URL");
    assert.equal(result.artifact.workspaces[0].variables?.contextBindings?.tenantId, "TENANT_ID");
  }
});

test("validateProjectArtifact fails closed when workspace variables.contextBindings uses reserved runtime keys", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        variables: {
          contextBindings: {
            "runtime.requestTimeoutMs": "REQ_TIMEOUT_MS",
            "probeBaseUrl": "PROBE_BASE_URL",
          },
        },
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /contextBindings\.runtime\.requestTimeoutMs is reserved/);
    assert.match(result.errors.join("\n"), /contextBindings\.probeBaseUrl is reserved/);
  }
});

test("validateProjectArtifact fails closed when runtime context mode is invalid", () => {
  const result = validateProjectArtifact({
      workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runtimeContexts: [{ name: "cluster", mode: "k8s" }],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.reasonCode, "runtime_context_unknown");
});

test("validateProjectArtifact fails closed when startups entry is provided without command", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runtimeContexts: [
          {
            name: "terminal-cli",
            mode: "terminal",
            startups: [{ name: "customers-service", args: ["-jar", "app.jar"] }],
          },
        ],
      }),
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
        withRequiredDefaults({
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          externalSystems: [{ name: "keycloak", kind: "auth-server", host: "localhost", port: 8081 }],
        }),
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

test("readProjectArtifact accepts UTF-8 BOM prefixed JSON", async () => {
  const root = createTestTempDir("project-artifact-bom");
  try {
    const out = path.join(root, ".mcpjvm", "my-project", "projects.json");
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(
      out,
      `\uFEFF${JSON.stringify({ workspaces: [withRequiredDefaults({ projectRoot: root })] }, null, 2)}\n`,
      "utf8",
    );

    const read = await readProjectArtifact(out);
    assert.equal(read.ok, true);
    if (read.ok) {
      assert.equal(read.artifact.workspaces[0].projectRoot, root);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("validateProjectArtifact accepts runPrerequisites with enum-constrained script/assert", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runPrerequisites: [
          {
            order: 1,
            id: "bootstrap",
            type: "script",
            onFail: "block",
            script: {
              command: "node",
              scriptPath: "scripts/bootstrap.js",
              args: ["--safe"],
              timeoutMs: 5000,
            },
          },
          {
            order: 2,
            id: "assert-auth",
            type: "assert",
            onFail: "block",
            assert: {
              kind: "env_exists",
              key: "AUTH_BEARER_TOKEN",
            },
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, true);
});

test("validateProjectArtifact fails closed for non-sequential runPrerequisites order", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runPrerequisites: [
          {
            order: 1,
            id: "bootstrap",
            type: "script",
            onFail: "block",
            script: { command: "node", scriptPath: "scripts/bootstrap.js" },
          },
          {
            order: 3,
            id: "assert-auth",
            type: "assert",
            onFail: "block",
            assert: { kind: "env_exists", key: "AUTH_BEARER_TOKEN" },
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
});

test("validateProjectArtifact accepts executionProfile runtimeContext alias and normalizes to runtimeContextName", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            runtimeContext: "terminal-cli",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "owners-list" }],
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.artifact.workspaces[0].executionProfiles?.[0].runtimeContextName, "terminal-cli");
  }
});

test("validateProjectArtifact accepts shared scripts and execution profile scriptRefs", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        envFile: ".mcpjvm/test-project/.env",
        variables: {
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
          keycloakClientIdEnv: "KEYCLOAK_CLIENT_ID",
          keycloakClientSecretEnv: "KEYCLOAK_CLIENT_SECRET",
          keycloakUsernameEnv: "KEYCLOAK_USERNAME",
          keycloakPasswordEnv: "KEYCLOAK_PASSWORD",
        },
        runtimeContexts: [{ name: "docker-compose-all", mode: "docker", composeFile: "docker/docker-compose-all.yml" }],
        scripts: [
          {
            name: "keycloak-token-bootstrap",
            phase: "postHealthcheck",
            command: "powershell",
            args: [
              "-NoProfile",
              "-ExecutionPolicy",
              "Bypass",
              "-File",
              ".mcpjvm/test-project/scripts/refresh-keycloak-token.ps1",
            ],
            appdir: ".",
            envFileArg: "-EnvFile",
          },
        ],
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            runtimeContextName: "docker-compose-all",
            executionPolicy: "stop_on_fail",
            scriptRefs: ["keycloak-token-bootstrap"],
            plans: [{ order: 1, planName: "course-service-regression-spec" }],
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    const workspace = result.artifact.workspaces[0];
    assert.equal(workspace.variables?.keycloakClientIdEnv, "KEYCLOAK_CLIENT_ID");
    assert.equal(workspace.scripts?.[0].envFileArg, "-EnvFile");
    assert.equal(workspace.executionProfiles?.[0].scriptRefs?.[0].name, "keycloak-token-bootstrap");
  }
});

test("validateProjectArtifact fails closed when execution profile scriptRef is unknown", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        scripts: [{ name: "seed-data", command: "node", args: ["scripts/seed.js"] }],
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            executionPolicy: "stop_on_fail",
            scriptRefs: [{ name: "missing-script", phase: "prePlan" }],
            plans: [{ order: 1, planName: "course-service-regression-spec" }],
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_reference_invalid");
    assert.match(result.errors.join("\n"), /scriptRefs\[0\]\.name must match/);
  }
});

test("validateProjectArtifact fails closed when execution profile scriptRef is provided without any shared scripts", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            executionPolicy: "stop_on_fail",
            scriptRefs: [{ name: "missing-script", phase: "prePlan" }],
            plans: [{ order: 1, planName: "course-service-regression-spec" }],
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_reference_invalid");
    assert.match(result.errors.join("\n"), /scriptRefs\[0\]\.name must match/);
  }
});

test("validateProjectArtifact fails closed when execution profile runtimeContextName is unknown", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            runtimeContextName: "missing-context",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "course-service-regression-spec" }],
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "runtime_context_unknown");
    assert.match(result.errors.join("\n"), /runtimeContextName must match/);
  }
});

test("readProjectArtifact fails closed when execution profile planName does not exist on disk", async () => {
  const root = createTestTempDir("project-artifact-missing-plan-ref");
  try {
    const out = path.join(root, ".mcpjvm", "my-project", "projects.json");
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(
      out,
      `${JSON.stringify(
        {
          workspaces: [
            withRequiredDefaults({
              projectRoot: root,
              executionProfiles: [
                {
                  executionProfile: "smoke",
                  executionPolicy: "stop_on_fail",
                  plans: [{ order: 1, planName: "missing-plan" }],
                },
              ],
            }),
          ],
        },
        null,
        2,
      )}\n`,
      "utf8",
    );

    const read = await readProjectArtifact(out);
    assert.equal(read.ok, false);
    if (!read.ok) {
      assert.equal(read.reasonCode, "project_reference_invalid");
      assert.match(read.errors.join("\n"), /planName must match an existing regression plan artifact/);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("readProjectArtifact validates performance execution profile plan refs against performance plan root", async () => {
  const root = createTestTempDir("project-artifact-performance-plan-ref");
  try {
    const projectRoot = path.join(root, "workspace");
    const out = path.join(root, ".mcpjvm", "my-project", "projects.json");
    const planRoot = path.join(root, ".mcpjvm", "my-project", "plans", "performance", "catalog-search-perf");
    fs.mkdirSync(planRoot, { recursive: true });
    fs.writeFileSync(path.join(planRoot, "metadata.json"), `${JSON.stringify({ specVersion: "0.1.0" }, null, 2)}\n`, "utf8");
    fs.writeFileSync(path.join(planRoot, "contract.json"), `${JSON.stringify({ entrypoints: [] }, null, 2)}\n`, "utf8");
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(
      out,
      `${JSON.stringify(
        {
          workspaces: [
            withRequiredDefaults({
              projectRoot,
              executionProfiles: [
                {
                  executionProfile: "catalog-perf-smoke",
                  suiteType: "performance",
                  executionPolicy: "stop_on_fail",
                  plans: [{ order: 1, planName: "catalog-search-perf" }],
                },
              ],
            }),
          ],
        },
        null,
        2,
      )}\n`,
      "utf8",
    );

    const read = await readProjectArtifact(out);
    assert.equal(read.ok, true);
    if (read.ok) {
      assert.equal(read.artifact.workspaces[0].executionProfiles?.[0].suiteType, "performance");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("validateProjectArtifact accepts sessionExport runtime defaults", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        sessionExport: {
          includeRuntimeStartup: true,
          includeHealthcheckGate: false,
          includeResolvedSecrets: true,
        },
      }),
    ],
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.artifact.workspaces[0]?.sessionExport?.includeResolvedSecrets, true);
  }
});

test("validateProjectArtifact fails closed when shared script uses absolute path args", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        scripts: [
          {
            name: "token-bootstrap",
            phase: "postHealthcheck",
            command: "powershell",
            args: ["-File", "C:\\Users\\Altheo\\scripts\\refresh-token.ps1"],
            appdir: ".",
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /scripts\[0\]\.args\[1\] must be relative\/replayable/);
  }
});

test("validateProjectArtifact fails closed when runPrerequisite scriptPath is absolute", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runPrerequisites: [
          {
            order: 1,
            id: "bootstrap",
            type: "script",
            onFail: "block",
            script: {
              command: "node",
              scriptPath: "C:\\workspace\\spring\\scripts\\bootstrap.js",
            },
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /runPrerequisites\[0\]\.script\.scriptPath must be relative\/replayable/);
  }
});

test("validateProjectArtifact fails closed when workspace envFile is absolute", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        envFile: "C:\\workspace\\spring\\.env",
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /workspaces\[0\]\.envFile must be relative\/replayable/);
  }
});

test("validateProjectArtifact fails closed when runPrerequisite script args contain absolute path", () => {
  const result = validateProjectArtifact({
    workspaces: [
      withRequiredDefaults({
        projectRoot: "C:\\workspace\\spring",
        runPrerequisites: [
          {
            order: 1,
            id: "bootstrap",
            type: "script",
            onFail: "block",
            script: {
              command: "node",
              scriptPath: "scripts/bootstrap.js",
              args: ["--seed", "C:\\workspace\\spring\\fixtures\\seed.json"],
            },
          },
        ],
      }),
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /runPrerequisites\[0\]\.script\.args\[1\] must be relative\/replayable/);
  }
});

test("validateProjectArtifact fails closed when orchestrator defaults are missing", () => {
  const result = validateProjectArtifact({
    workspaces: [{ projectRoot: "C:\\workspace\\spring" }],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /defaults\.orchestrator is required/);
  }
});

test("validateProjectArtifact fails closed when orchestrator defaults are invalid", () => {
  const result = validateProjectArtifact({
    workspaces: [
      {
        projectRoot: "C:\\workspace\\spring",
        defaults: {
          orchestrator: {
            resumePollMax: 0,
            resumePollIntervalMs: 10_000,
            resumePollTimeoutMs: 5_000,
          },
        },
      },
    ],
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.equal(result.reasonCode, "project_artifact_invalid");
    assert.match(result.errors.join("\n"), /resumePollMax must be a positive integer/);
    assert.match(result.errors.join("\n"), /resumePollTimeoutMs must be >= resumePollIntervalMs/);
  }
});
