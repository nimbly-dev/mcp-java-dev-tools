const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { DatabaseSync } = require("node:sqlite");

const { executeRegressionPlanWorkflow } = require("@tools-regression-execution-plan-spec/regression_plan_executor.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("executeRegressionPlanWorkflow executes SQL external verification against runtime-owned connection config", async () => {
  const root = createTestTempDir("plan-executor-external-verification-sql-pass");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-sql-pass";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    const dbPath = path.join(root, "catalog.sqlite");
    const db = new DatabaseSync(dbPath);
    try {
      db.exec(`
        CREATE TABLE reindex_status (
          tenant_id TEXT NOT NULL,
          indexed_count INTEGER NOT NULL
        );
      `);
      const insert = db.prepare("INSERT INTO reindex_status (tenant_id, indexed_count) VALUES (?, ?)");
      insert.run("tenant-social-001", 500);
    } finally {
      db.close();
    }

    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_row_count",
          provider: { type: "sql" },
          request: {
            sql: {
              connectionRef: "catalogDb",
              statement: "SELECT indexed_count FROM reindex_status WHERE tenant_id = :tenantId",
              parameters: [{ name: "tenantId", valueFromContext: "tenantId" }],
            },
          },
          expect: [
            { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
            { id: "indexed_count_ok", actualPath: "sql.firstRow.indexed_count", operator: "numeric_gte", expected: 500 },
          ],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      providedContext: {
        tenantId: "tenant-social-001",
        "sql.connection.catalogDb.kind": "sqlite",
        "sql.connection.catalogDb.sqlite.filePath": dbPath,
        "sql.connection.catalogDb.password": "SHOULD_NOT_PERSIST",
      },
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "pass",
            statusCode: 202,
            durationMs: 8,
            body: "{\"accepted\":true}",
            bodyPreview: "{\"accepted\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.externalVerificationStatus, "pass");
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "pass");
      assert.equal(out.executionResult.externalVerification?.[0]?.sql?.rowCount, 1);
      assert.equal(out.executionResult.externalVerification?.[0]?.sql?.firstRow?.indexed_count, 500);
      const contextResolved = JSON.parse(fs.readFileSync(out.artifacts.contextResolvedPathAbs, "utf8"));
      assert.equal(contextResolved.redaction.resolvedSecretKeyCount, 3);
      assert.deepEqual(contextResolved.redaction.resolvedSecretKeysOmitted, [
        "sql.connection.catalogDb.kind",
        "[REDACTED]",
        "sql.connection.catalogDb.sqlite.filePath",
      ]);
      assert.equal(typeof contextResolved["sql.connection.catalogDb.kind"], "undefined");
      assert.equal(typeof contextResolved["sql.connection.catalogDb.password"], "undefined");
      assert.equal(typeof contextResolved["sql.connection.catalogDb.sqlite.filePath"], "undefined");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
