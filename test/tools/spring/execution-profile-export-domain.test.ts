const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executionProfileExportDomain } = require("@/tools/core/execution_profile_export/domain");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function writeProject(root: string): void {
  const projectName = "test-project";
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "setup-js",
              phase: "prePlan",
              command: "node",
              args: [".mcpjvm/test-project/scripts/setup.js"],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-js", phase: "prePlan" }],
              plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
            },
            {
            executionProfile: "alternate-run",
            executionPolicy: "continue_on_fail",
            plans: [{ order: 1, planName: "alternate-spec" }],
          },
        ],
      },
    ],
  });
}

function writeProjectWithName(root: string, projectName: string, executionProfile: string, planName: string): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: root,
        executionProfiles: [
          {
            executionProfile,
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName }],
          },
        ],
      },
    ],
  });
}

function writePlanContract(root: string, planName: string, projectName = "test-project"): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [],
    steps: [
      {
        order: 1,
        id: "health_check",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            url: "http://127.0.0.1:8080/actuator/health",
          },
        },
        expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
      },
    ],
  });
}

function readJson(filePath: string): any {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

test("executionProfileExportDomain resolves executionProfile and creates a fresh sh export", async () => {
  const root = createTestTempDir("execution-profile-export-domain-sh");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "sh");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-regression-test-run$/);
    assert.match(
      String(out.structuredContent.output?.scriptPathAbs ?? ""),
      /exports[\\/]\d{4}-\d{2}-\d{2}-[0-9a-f-]+[\\/]run-execution-profile\.sh$/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when mode/type is missing", async () => {
  const root = createTestTempDir("execution-profile-export-domain-mode-required");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
    });
    assert.equal(out.structuredContent.status, "execution_export_mode_required");
    assert.equal(out.structuredContent.reasonCode, "execution_export_mode_required");
    assert.equal(out.structuredContent.reasonMeta.nextAction, "provide mode=ps1|sh|postman");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when mode/type conflict", async () => {
  const root = createTestTempDir("execution-profile-export-domain-mode-conflict");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "sh",
      type: "ps1",
    });
    assert.equal(out.structuredContent.status, "execution_export_mode_conflict");
    assert.equal(out.structuredContent.reasonCode, "execution_export_mode_conflict");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when executionProfile is ambiguous and no selector is provided", async () => {
  const root = createTestTempDir("execution-profile-export-domain-default");
  try {
    writeProject(root);
    writePlanContract(root, "alternate-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "execution_profile_ambiguous");
    assert.equal(out.structuredContent.reasonCode, "execution_profile_ambiguous");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves project ambiguity when projectName is explicitly provided", async () => {
  const root = createTestTempDir("execution-profile-export-domain-project-selector");
  try {
    writeProjectWithName(root, "test-project", "regression-test-run", "gateway-route-smoke-spec");
    writeProjectWithName(root, "test-project-performance", "test-performance-contract-run", "mcp-tool-performance-replay-spec");
    writePlanContract(root, "mcp-tool-performance-replay-spec", "test-project-performance");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      projectName: "test-project-performance",
      executionProfile: "test-performance-contract-run",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-test-performance-contract-run$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves containing execution profile by planName selector", async () => {
  const root = createTestTempDir("execution-profile-export-domain-plan-selector");
  try {
    writeProject(root);
    writePlanContract(root, "alternate-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const x = 1;\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      mode: "sh",
      planName: "alternate-spec",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-alternate-run$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain exports postman collection when scripts are JS-compatible", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(
      path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"),
      "pm.environment.set('token', 'x');\n",
      "utf8",
    );

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "postman");
    assert.match(String(out.structuredContent.output?.collectionPathAbs ?? ""), /collection\.postman\.json$/);
    assert.match(String(out.structuredContent.output?.environmentPathAbs ?? ""), /environment\.postman\.json$/);
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    assert.equal(collection.info.schema, "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
    assert.equal(environment._postman_variable_scope, "environment");
    assert.equal(typeof environment._postman_exported_at, "undefined");
    assert.equal(typeof collection.item[0].request.url, "string");

    const out2 = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    const collection2 = readJson(String(out2.structuredContent.output?.collectionPathAbs));
    const environment2 = readJson(String(out2.structuredContent.output?.environmentPathAbs));
    assert.deepEqual(collection2, collection);
    assert.deepEqual(environment2, environment);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain normalizes ${var} syntax and emits referenced environment variables", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-vars");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "POST",
              url: "http://127.0.0.1:9001/api/courses",
              headers: { Authorization: "Bearer ${auth.bearer}" },
              body: { title: "${courseTitle}" },
            },
          },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    assert.equal(collection.item[0].request.url, "http://127.0.0.1:9001/api/courses");
    assert.equal(collection.item[0].request.header[0].value, "Bearer {{auth.bearer}}");
    assert.match(collection.item[0].request.body.raw, /"title": "\{\{courseTitle\}\}"/);
    const envKeys = environment.values.map((entry: any) => entry.key).sort();
    assert.deepEqual(envKeys, ["auth.bearer", "courseTitle"]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when url authority variable has no default", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-url-default-missing");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: "${apiBaseUrl}/api/courses" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.cause, "url_variable_default_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain applies prerequisite defaults and uses gatewayBaseUrl variable as URL authority", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-gateway-default");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "gatewayBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://service-gateway" }],
      steps: [
        {
          order: 1,
          id: "route_courses_via_gateway",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "${gatewayBaseUrl}/courses" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    assert.equal(collection.item[0].request.url, "{{gatewayBaseUrl}}/courses");
    const gatewayVar = environment.values.find((entry: any) => entry.key === "gatewayBaseUrl");
    assert.equal(gatewayVar.value, "http://service-gateway");
    const authorVar = environment.values.find((entry: any) => entry.key === "courseAuthor");
    if (authorVar) {
      assert.equal(authorVar.type, "default");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves auth.bearer from workspace env when includeResolvedSecrets=true", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-auth-resolved");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".env",
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [{ executionProfile: "regression-test-run", executionPolicy: "stop_on_fail", scriptRefs: [{ name: "setup-js", phase: "prePlan" }], plans: [{ order: 1, planName: "gateway-route-smoke-spec" }] }],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.writeFileSync(path.join(root, ".env"), "AUTH_BEARER_TOKEN=secret-token\n", "utf8");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
      includeResolvedSecrets: true,
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "secret-token");
    assert.equal(authVar.type, "secret");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves auth.bearer from sessionExport includeResolvedSecrets default", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-auth-session-default");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".env",
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          sessionExport: { includeResolvedSecrets: true },
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [{ executionProfile: "regression-test-run", executionPolicy: "stop_on_fail", scriptRefs: [{ name: "setup-js", phase: "prePlan" }], plans: [{ order: 1, planName: "gateway-route-smoke-spec" }] }],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.writeFileSync(path.join(root, ".env"), "AUTH_BEARER_TOKEN=session-default-token\n", "utf8");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "session-default-token");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves required auth.bearer via contextBindings env key mapping", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-binding-map");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: ".env",
          scripts: [{ name: "setup-js", phase: "prePlan", command: "node", args: [".mcpjvm/test-project/scripts/setup.js"] }],
          executionProfiles: [{ executionProfile: "regression-test-run", executionPolicy: "stop_on_fail", scriptRefs: [{ name: "setup-js", phase: "prePlan" }], plans: [{ order: 1, planName: "gateway-route-smoke-spec" }] }],
        },
      ],
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.writeFileSync(path.join(root, ".env"), "TOKEN_KEY=bind-token\n", "utf8");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
      contextBindings: { "auth.bearer": "TOKEN_KEY" },
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "bind-token");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed when required prerequisite remains unresolved", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-required-unresolved");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.cause, "required_prerequisite_unresolved");
    assert.equal(out.structuredContent.reasonMeta.prerequisiteKey, "auth.bearer");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves required prerequisite from contextValues without env", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-context-values");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "auth.bearer", required: true, secret: true, provisioning: "user_input" }],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "http", transport: { http: { method: "GET", url: "http://127.0.0.1:8080/x", headers: { Authorization: "Bearer ${auth.bearer}" } } }, expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }] }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
      contextValues: { "auth.bearer": "ctx-token" },
    });
    assert.equal(out.structuredContent.status, "ok");
    const environment = readJson(String(out.structuredContent.output?.environmentPathAbs));
    const authVar = environment.values.find((entry: any) => entry.key === "auth.bearer");
    assert.equal(authVar.value, "ctx-token");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain supports derived required prerequisite via postman extract before downstream use", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-derived-context");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [{ key: "courseId", required: true, secret: false, provisioning: "user_input" }],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: "http://127.0.0.1:9001/api/courses", body: { title: "x" } } },
          extract: [{ from: "response.body.courseId", as: "courseId" }],
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
        {
          order: 2,
          id: "use_course_id",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://127.0.0.1:8080/api/course/{{courseId}}" } },
          expect: [{ id: "e2", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });
    assert.equal(out.structuredContent.status, "ok");
    const collection = readJson(String(out.structuredContent.output?.collectionPathAbs));
    assert.equal(collection.item[1].request.url, "http://127.0.0.1:8080/api/course/{{courseId}}");
    assert.equal(collection.item[0].event?.[0]?.listen, "test");
    const execLines = collection.item[0].event?.[0]?.script?.exec ?? [];
    assert.ok(execLines.some((line: string) => line.includes("pm.environment.set(\"courseId\"")));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain executes non-JS prerequisite script before postman export", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-blocked");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "setup-ps1",
              phase: "prePlan",
              command: "powershell",
              args: ["-File", ".mcpjvm/test-project/scripts/setup.ps1"],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-ps1", phase: "prePlan" }],
              plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
            },
          ],
        },
      ],
    });
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.ps1"), "Write-Output 'x'\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "postman");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when JS script is not Postman-compatible", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-js-format");
  try {
    writeProject(root);
    writePlanContract(root, "gateway-route-smoke-spec");
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "const token = 'x';\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_script_invalid_format");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_script_validation");
    assert.equal(out.structuredContent.reasonMeta.scriptName, "setup-js");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when script implies provisioning", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-provisioning");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          scripts: [
            {
              name: "setup-provision",
              phase: "preRuntime",
              command: "docker",
              args: ["compose", "up", "-d"],
            },
          ],
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              scriptRefs: [{ name: "setup-provision", phase: "preRuntime" }],
              plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
            },
          ],
        },
      ],
    });
    writePlanContract(root, "gateway-route-smoke-spec");
    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_provisioning_not_supported");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_scope_guard");
    assert.equal(out.structuredContent.reasonMeta.scriptName, "setup-provision");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when plan step transport is non-http", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-transport");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [{ order: 1, id: "s1", targetRef: 0, protocol: "probe", transport: {} }],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_export_render");
    assert.equal(out.structuredContent.reasonMeta.cause, "unsupported_transport");
    assert.equal(out.structuredContent.reasonMeta.planName, "gateway-route-smoke-spec");
    assert.equal(out.structuredContent.reasonMeta.stepId, "s1");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when url is unresolved", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-url");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "s1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_export_render");
    assert.equal(out.structuredContent.reasonMeta.cause, "url_unresolved");
    assert.equal(out.structuredContent.reasonMeta.planName, "gateway-route-smoke-spec");
    assert.equal(out.structuredContent.reasonMeta.stepId, "s1");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain fails closed for postman when url is not runner-runnable", async () => {
  const root = createTestTempDir("execution-profile-export-domain-postman-unrunnable-url");
  try {
    writeProject(root);
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-route-smoke-spec", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "s1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "/api/courses" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    fs.mkdirSync(path.join(root, ".mcpjvm", "test-project", "scripts"), { recursive: true });
    fs.writeFileSync(path.join(root, ".mcpjvm", "test-project", "scripts", "setup.js"), "pm.environment.set('ok','1');\n", "utf8");

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "postman",
    });

    assert.equal(out.structuredContent.resultType, "report");
    assert.equal(out.structuredContent.reasonCode, "postman_export_blocked");
    assert.equal(out.structuredContent.reasonMeta.failedStep, "postman_export_render");
    assert.equal(out.structuredContent.reasonMeta.cause, "url_unrunnable");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
