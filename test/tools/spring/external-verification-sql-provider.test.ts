const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { DatabaseSync } = require("node:sqlite");

const {
  executeSqlExternalVerification,
} = require("@tools-regression-execution-plan-spec/external_verification_sql_provider.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function seedCatalogDb(dbPath: string): void {
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
    insert.run("tenant-social-002", 250);
  } finally {
    db.close();
  }
}

test("executeSqlExternalVerification executes context-bound SQL parameters and normalizes canonical result paths", async () => {
  const root = createTestTempDir("external-verification-sql-pass");
  const dbPath = path.join(root, "catalog.sqlite");
  try {
    seedCatalogDb(dbPath);
    const out = await executeSqlExternalVerification({
      workspaceRootAbs: root,
      resolvedContext: {
        tenantId: "tenant-social-001",
        "sql.connection.catalogDb.kind": "sqlite",
        "sql.connection.catalogDb.sqlite.filePath": dbPath,
      },
      verification: {
        id: "verify_catalog_count",
        provider: { type: "sql" },
        request: {
          sql: {
            connectionRef: "catalogDb",
            statement: `
              SELECT indexed_count
              FROM reindex_status
              WHERE tenant_id = :tenantId AND indexed_count = :expectedCount
            `,
            parameters: [
              { name: "tenantId", valueFromContext: "tenantId" },
              { name: "expectedCount", value: 500 },
            ],
          },
        },
        expect: [
          { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
          { id: "indexed_count_ok", actualPath: "sql.firstRow.indexed_count", operator: "numeric_gte", expected: 500 },
        ],
      },
    });

    assert.equal(out.result.status, "pass");
    assert.equal(out.result.providerType, "sql");
    assert.equal(out.result.sql.rowCount, 1);
    assert.equal(out.result.sql.firstRow.indexed_count, 500);
    assert.equal(out.result.assertions[0].status, "pass");
    assert.equal(out.result.assertions[1].status, "pass");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeSqlExternalVerification fails closed when connectionRef runtime config is missing", async () => {
  const out = await executeSqlExternalVerification({
    workspaceRootAbs: process.cwd(),
    resolvedContext: {},
    verification: {
      id: "verify_missing_connection",
      provider: { type: "sql" },
      request: {
        sql: {
          connectionRef: "catalogDb",
          statement: "SELECT 1",
        },
      },
      expect: [{ id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 }],
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_connection_unresolved");
  assert.equal(out.result.reasonMeta.missingContextKey, "sql.connection.catalogDb.kind");
});

test("executeSqlExternalVerification fails closed when context-derived SQL parameter is unresolved", async () => {
  const root = createTestTempDir("external-verification-sql-parameter-missing");
  const dbPath = path.join(root, "catalog.sqlite");
  try {
    seedCatalogDb(dbPath);
    const out = await executeSqlExternalVerification({
      workspaceRootAbs: root,
      resolvedContext: {
        "sql.connection.catalogDb.kind": "sqlite",
        "sql.connection.catalogDb.sqlite.filePath": dbPath,
      },
      verification: {
        id: "verify_missing_parameter",
        provider: { type: "sql" },
        request: {
          sql: {
            connectionRef: "catalogDb",
            statement: "SELECT indexed_count FROM reindex_status WHERE tenant_id = :tenantId",
            parameters: [{ name: "tenantId", valueFromContext: "tenantId" }],
          },
        },
        expect: [{ id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 }],
      },
    });

    assert.equal(out.result.status, "blocked_runtime");
    assert.equal(out.result.reasonCode, "external_verification_request_unresolved");
    assert.equal(out.result.reasonMeta.parameterName, "tenantId");
    assert.equal(out.result.reasonMeta.missingContextKey, "tenantId");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeSqlExternalVerification fails closed when sql.firstRow.* is asserted without any returned row", async () => {
  const root = createTestTempDir("external-verification-sql-first-row-missing");
  const dbPath = path.join(root, "catalog.sqlite");
  try {
    seedCatalogDb(dbPath);
    const out = await executeSqlExternalVerification({
      workspaceRootAbs: root,
      resolvedContext: {
        tenantId: "tenant-missing",
        "sql.connection.catalogDb.kind": "sqlite",
        "sql.connection.catalogDb.sqlite.filePath": dbPath,
      },
      verification: {
        id: "verify_first_row_missing",
        provider: { type: "sql" },
        request: {
          sql: {
            connectionRef: "catalogDb",
            statement: "SELECT indexed_count FROM reindex_status WHERE tenant_id = :tenantId",
            parameters: [{ name: "tenantId", valueFromContext: "tenantId" }],
          },
        },
        expect: [
          { id: "first_row_exists", actualPath: "sql.firstRow.indexed_count", operator: "numeric_gte", expected: 1 },
        ],
      },
    });

    assert.equal(out.result.status, "blocked_runtime");
    assert.equal(out.result.reasonCode, "external_verification_response_invalid");
    assert.equal(out.result.assertions[0].status, "blocked");
    assert.equal(out.result.assertions[0].reasonCode, "actual_path_missing");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeSqlExternalVerification fails closed when SQL execution throws", async () => {
  const root = createTestTempDir("external-verification-sql-execution-failure");
  const dbPath = path.join(root, "catalog.sqlite");
  try {
    seedCatalogDb(dbPath);
    const out = await executeSqlExternalVerification({
      workspaceRootAbs: root,
      resolvedContext: {
        "sql.connection.catalogDb.kind": "sqlite",
        "sql.connection.catalogDb.sqlite.filePath": dbPath,
      },
      verification: {
        id: "verify_bad_statement",
        provider: { type: "sql" },
        request: {
          sql: {
            connectionRef: "catalogDb",
            statement: "SELECT missing_column FROM reindex_status",
          },
        },
        expect: [{ id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 }],
      },
    });

    assert.equal(out.result.status, "blocked_runtime");
    assert.equal(out.result.reasonCode, "external_verification_execution_failed");
    assert.equal(out.result.reasonMeta.connectionRef, "catalogDb");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeSqlExternalVerification fails closed when SQL execution exceeds timeoutMs", async () => {
  const out = await executeSqlExternalVerification({
    workspaceRootAbs: process.cwd(),
    resolvedContext: {
      "sql.connection.catalogDb.kind": "sqlite",
      "sql.connection.catalogDb.sqlite.filePath": ":memory:",
    },
    verification: {
      id: "verify_sql_timeout",
      provider: { type: "sql" },
      request: {
        sql: {
          connectionRef: "catalogDb",
          statement: `
            WITH RECURSIVE cnt(x) AS (
              SELECT 1
              UNION ALL
              SELECT x + 1 FROM cnt WHERE x < 5000000
            )
            SELECT sum(x) AS sum_x FROM cnt
          `,
          timeoutMs: 10,
        },
      },
      expect: [{ id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 }],
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_execution_failed");
  assert.match(String(out.result.reasonMeta.errorMessage ?? ""), /sql_execution_timeout_/);
  assert.equal(out.result.reasonMeta.timeoutMs, 10);
});
