const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const {
  buildReplayPreflightWithDiscovery,
  resolveDiscoverablePrerequisites,
} = require("@tools-regression-execution-plan-spec/regression_discovery_resolver.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

async function createHttpServer(): Promise<{ server: any; baseUrl: string }> {
  return await new Promise((resolve, reject) => {
    const server = http.createServer((_req: any, res: any) => {
      res.statusCode = 200;
      res.end("ok");
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      resolve({ server, baseUrl: `http://127.0.0.1:${port}` });
    });
  });
}

async function reservePortAndRelease(): Promise<number> {
  return await new Promise((resolve, reject) => {
    const server = http.createServer((_req: any, res: any) => {
      res.statusCode = 200;
      res.end("ok");
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      server.close((err: Error | undefined) => {
        if (err) reject(err);
        else resolve(port);
      });
    });
  });
}

function baseMetadata(overrides = {}) {
  return {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: true,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
      ...overrides,
    },
  };
}

function baseContract(overrides = {}) {
  return {
    targets: [
      {
        type: "class_method",
        selectors: {
          fqcn: "com.example.social.post.app.controller.PostController",
          method: "createPost",
        },
      },
    ],
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "datasource",
      },
      {
        key: "region",
        required: false,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "runtime_context",
        default: "ap-southeast-1",
      },
      {
        key: "auth.bearer",
        required: true,
        secret: true,
        provisioning: "user_input",
      },
    ],
    steps: [
      {
        order: 1,
        id: "create_post",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: { method: "POST", pathTemplate: "/api/v1/posts" },
        },
        expect: [
          {
            id: "step_outcome_pass",
            actualPath: "status",
            operator: "outcome_status",
            expected: "pass",
          },
        ],
      },
    ],
    ...overrides,
  };
}

test("resolveDiscoverablePrerequisites resolves discoverable context with datasource adapter", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {
      datasource: async () => ({
        accessMode: "read",
        outcome: "resolved",
        value: "tenant-social-001",
        sourceRef: "table:tenants",
      }),
    },
  });
  assert.equal(result.status, "resolved");
  assert.equal(result.reasonCode, "ok");
  assert.equal(result.discoveredContext.tenantId, "tenant-social-001");
});

test("resolveDiscoverablePrerequisites blocks with policy-disabled reason", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata({ discoveryPolicy: "disabled" }),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {
      datasource: async () => ({ accessMode: "read", outcome: "resolved", value: "tenant-social-001" }),
    },
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discoverable_prerequisite_policy_disabled");
});

test("resolveDiscoverablePrerequisites blocks on ambiguous datasource result", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {
      datasource: async () => ({ accessMode: "read", outcome: "unresolved_ambiguous", candidateCount: 3 }),
    },
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discovery_ambiguous_result");
});

test("resolveDiscoverablePrerequisites blocks on empty datasource result", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {
      datasource: async () => ({ accessMode: "read", outcome: "unresolved_empty", candidateCount: 0 }),
    },
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discovery_empty_result");
});

test("resolveDiscoverablePrerequisites blocks on unsupported discovery source adapter", async () => {
  const contract = baseContract({
    prerequisites: [
      {
        key: "tenantId",
        required: true,
        secret: false,
        provisioning: "discoverable",
        discoverySource: "runtime_context",
      },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
  });
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {},
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discovery_source_unsupported");
});

test("resolveDiscoverablePrerequisites blocks on timeout", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    timeoutMs: 20,
    adapters: {
      datasource: async () => {
        await new Promise((resolve) => setTimeout(resolve, 200));
        return { accessMode: "read", outcome: "resolved", value: "tenant-social-001" };
      },
    },
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discovery_timeout");
});

test("resolveDiscoverablePrerequisites blocks on adapter runtime failure", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {
      datasource: async () => {
        throw new Error("db_connection_refused");
      },
    },
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discovery_adapter_failure");
});

test("buildReplayPreflightWithDiscovery merges discovered context and becomes ready", async () => {
  const result = await buildReplayPreflightWithDiscovery({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    targetCandidateCount: 1,
    adapters: {
      datasource: async () => ({ accessMode: "read", outcome: "resolved", value: "tenant-social-001" }),
    },
  });
  assert.equal(result.preflight.status, "ready");
  assert.equal(result.resolvedContext.tenantId, "tenant-social-001");
});

test("buildReplayPreflightWithDiscovery enforces precedence user > discovered > default", async () => {
  const result = await buildReplayPreflightWithDiscovery({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token", tenantId: "tenant-manual-007" },
    targetCandidateCount: 1,
    adapters: {
      datasource: async () => ({ accessMode: "read", outcome: "resolved", value: "tenant-social-001" }),
    },
  });
  assert.equal(result.preflight.status, "ready");
  assert.equal(result.resolvedContext.tenantId, "tenant-manual-007");
});

test("buildReplayPreflightWithDiscovery returns blocked_ambiguous preflight on ambiguous discovery", async () => {
  const result = await buildReplayPreflightWithDiscovery({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    targetCandidateCount: 1,
    adapters: {
      datasource: async () => ({ accessMode: "read", outcome: "unresolved_ambiguous", candidateCount: 2 }),
    },
  });
  assert.equal(result.preflight.status, "blocked_ambiguous");
  assert.equal(result.preflight.reasonCode, "discovery_ambiguous_result");
});

test("resolveDiscoverablePrerequisites blocks when adapter attempts write/mutation mode", async () => {
  const result = await resolveDiscoverablePrerequisites({
    metadata: baseMetadata(),
    contract: baseContract(),
    providedContext: { "auth.bearer": "runtime-token" },
    adapters: {
      datasource: async () => ({
        accessMode: "write",
        outcome: "resolved",
        value: "tenant-social-001",
      }),
    },
  });
  assert.equal(result.status, "blocked");
  assert.equal(result.reasonCode, "discovery_mutation_blocked");
});

test("buildReplayPreflightWithDiscovery applies project context env auth before discovery", async () => {
  const root = createTestTempDir("project-context-discovery");
  try {
    const projectsFileAbs = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projectsFileAbs, {
      workspaces: [
        {
          projectRoot: root,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
        },
      ],
    });
    const contract = baseContract({
      prerequisites: [
        {
          key: "tenantId",
          required: true,
          secret: false,
          provisioning: "discoverable",
          discoverySource: "datasource",
        },
        {
          key: "auth.bearer",
          required: true,
          secret: true,
          provisioning: "user_input",
        },
      ],
    });
    const result = await buildReplayPreflightWithDiscovery({
      metadata: baseMetadata(),
      contract,
      providedContext: {},
      targetCandidateCount: 1,
      projectContextOptions: {
        workspaceRootAbs: root,
        projectsFileAbs,
        env: { AUTH_BEARER_TOKEN: "runtime-token-from-env" },
        healthChecksEnabled: false,
      },
      adapters: {
        datasource: async () => ({ accessMode: "read", outcome: "resolved", value: "tenant-social-001" }),
      },
    });

    assert.equal(result.preflight.status, "ready");
    assert.equal(result.resolvedContext["auth.bearer"], "runtime-token-from-env");
    assert.equal(result.resolvedContext.tenantId, "tenant-social-001");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("buildReplayPreflightWithDiscovery fails closed when project context env key is missing", async () => {
  const root = createTestTempDir("project-context-discovery-env-missing");
  try {
    const projectsFileAbs = path.join(root, ".mcpjvm", "my-project", "projects.json");
    writeJson(projectsFileAbs, {
      workspaces: [
        {
          projectRoot: root,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
        },
      ],
    });
    const result = await buildReplayPreflightWithDiscovery({
      metadata: baseMetadata(),
      contract: baseContract(),
      providedContext: {},
      targetCandidateCount: 1,
      projectContextOptions: {
        workspaceRootAbs: root,
        projectsFileAbs,
        env: {},
        healthChecksEnabled: false,
      },
      adapters: {},
    });

    assert.equal(result.preflight.status, "needs_user_input");
    assert.equal(result.preflight.reasonCode, "env_key_missing");
    assert.deepEqual(result.preflight.missing, ["AUTH_BEARER_TOKEN"]);
    assert.equal(result.preflight.nextAction, "Set AUTH_BEARER_TOKEN in .env or environment and retry.");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("buildReplayPreflightWithDiscovery fails closed when probeVerification=true and probeBaseUrl is unreachable", async () => {
  const closedPort = await reservePortAndRelease();
  const contract = baseContract({
    prerequisites: [
      {
        key: "probeBaseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: `http://127.0.0.1:${closedPort}`,
      },
      {
        key: "auth.bearer",
        required: true,
        secret: true,
        provisioning: "user_input",
      },
    ],
  });
  const result = await buildReplayPreflightWithDiscovery({
    metadata: baseMetadata(),
    contract,
    providedContext: { "auth.bearer": "runtime-token" },
    targetCandidateCount: 1,
    adapters: {},
  });
  assert.equal(result.preflight.status, "needs_user_input");
  assert.equal(result.preflight.reasonCode, "external_healthcheck_failed");
  assert.match((result.preflight.checks ?? [])[0] ?? "", new RegExp(`^probe:http://127\\.0\\.0\\.1:${closedPort}=unreachable$`));
});

test("buildReplayPreflightWithDiscovery remains ready when probeVerification=true and probeBaseUrl is reachable", async () => {
  const { server, baseUrl } = await createHttpServer();
  try {
    const contract = baseContract({
      prerequisites: [
        {
          key: "probeBaseUrl",
          required: true,
          secret: false,
          provisioning: "user_input",
          default: baseUrl,
        },
        {
          key: "auth.bearer",
          required: true,
          secret: true,
          provisioning: "user_input",
        },
      ],
    });
    const result = await buildReplayPreflightWithDiscovery({
      metadata: baseMetadata(),
      contract,
      providedContext: { "auth.bearer": "runtime-token" },
      targetCandidateCount: 1,
      adapters: {},
    });
    assert.equal(result.preflight.status, "ready");
    assert.equal(result.preflight.reasonCode, "ok");
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
