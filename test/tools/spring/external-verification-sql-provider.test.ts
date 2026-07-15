const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { DatabaseSync } = require("node:sqlite");

const { executeSqlExternalVerification } = require("@tools-feature-regression-suite");

type PostgresqlExecutionMockArgs = {
  connection: {
    kind: "postgresql";
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    tlsMode: "disable" | "require" | "verify-full";
  };
  statement: string;
  bindings: Array<{
    name: string;
    value: unknown;
  }>;
};

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
    const insert = db.prepare(
      "INSERT INTO reindex_status (tenant_id, indexed_count) VALUES (?, ?)",
    );
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
          {
            id: "indexed_count_ok",
            actualPath: "sql.firstRow.indexed_count",
            operator: "numeric_gte",
            expected: 500,
          },
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
      expect: [
        { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
      ],
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_connection_unresolved");
  assert.equal(out.result.reasonMeta.missingContextKey, "sql.connection.catalogDb.kind");
});

test("executeSqlExternalVerification rejects JDBC keys even for SQLite connections", async () => {
  const tempDir = createTestTempDir("sql-provider-sqlite-jdbc");
  const dbPath = path.join(tempDir, "catalog.sqlite");
  seedCatalogDb(dbPath);
  try {
    const out = await executeSqlExternalVerification({
      workspaceRootAbs: process.cwd(),
      resolvedContext: {
        "sql.connection.catalogDb.kind": "sqlite",
        "sql.connection.catalogDb.sqlite.filePath": dbPath,
        "sql.connection.catalogDb.jdbc.url": "jdbc:sqlite:catalog.sqlite",
      },
      verification: {
        id: "verify_sqlite_jdbc_rejected",
        provider: { type: "sql" },
        request: { sql: { connectionRef: "catalogDb", statement: "SELECT 1" } },
        expect: [],
      },
    });
    assert.equal(out.result.reasonCode, "external_verification_connection_unsupported");
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
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
        expect: [
          { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
        ],
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
          {
            id: "first_row_exists",
            actualPath: "sql.firstRow.indexed_count",
            operator: "numeric_gte",
            expected: 1,
          },
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
        expect: [
          { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
        ],
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
      expect: [
        { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
      ],
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_execution_failed");
  assert.equal(out.result.reasonMeta.cause, "sql_execution_timeout_10ms");
  assert.equal(out.result.reasonMeta.timeoutMs, 10);
});

test("executeSqlExternalVerification executes PostgreSQL verification with bound parameters", async () => {
  const out = await executeSqlExternalVerification({
    workspaceRootAbs: process.cwd(),
    resolvedContext: {
      tenantId: "tenant-social-001",
      "sql.connection.catalogDb.kind": "postgresql",
      "sql.connection.catalogDb.host": "db.internal",
      "sql.connection.catalogDb.port": 5432,
      "sql.connection.catalogDb.database": "catalog",
      "sql.connection.catalogDb.username": "catalog_user",
      "sql.connection.catalogDb.password": " catalog_pass ",
      "sql.connection.catalogDb.tls.mode": "require",
    },
    verification: {
      id: "verify_catalog_count_postgresql",
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
        {
          id: "indexed_count_min",
          actualPath: "sql.firstRow.indexed_count",
          operator: "numeric_gte",
          expected: "500.12345678901234567889",
        },
        {
          id: "indexed_count_exact",
          actualPath: "sql.firstRow.indexed_count",
          operator: "field_equals",
          expected: "500.12345678901234567890",
        },
      ],
    },
    internals: {
      executePostgresqlQuery: async ({
        connection,
        statement,
        bindings,
      }: PostgresqlExecutionMockArgs) => {
        assert.deepEqual(connection, {
          kind: "postgresql",
          host: "db.internal",
          port: 5432,
          database: "catalog",
          username: "catalog_user",
          password: " catalog_pass ",
          tlsMode: "require",
        });
        assert.equal(
          statement,
          "SELECT indexed_count FROM reindex_status WHERE tenant_id = :tenantId",
        );
        assert.deepEqual(bindings, [{ name: "tenantId", value: "tenant-social-001" }]);
        return {
          ok: true,
          durationMs: 18,
          rows: [{ indexed_count: "500.12345678901234567890" }],
        };
      },
    },
  });

  assert.equal(out.result.status, "pass");
  assert.equal(out.result.providerType, "sql");
  assert.equal(out.result.sql.rowCount, 1);
  assert.equal(out.result.sql.firstRow.indexed_count, "500.12345678901234567890");
  assert.equal(out.result.assertions[1].status, "pass");
  assert.equal(out.result.assertions[2].status, "pass");
});

test("executeSqlExternalVerification fails closed when PostgreSQL runtime config is incomplete", async () => {
  const out = await executeSqlExternalVerification({
    workspaceRootAbs: process.cwd(),
    resolvedContext: {
      "sql.connection.catalogDb.kind": "postgresql",
    },
    verification: {
      id: "verify_missing_postgresql_host",
      provider: { type: "sql" },
      request: {
        sql: {
          connectionRef: "catalogDb",
          statement: "SELECT 1",
        },
      },
      expect: [
        { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
      ],
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_connection_unresolved");
  assert.equal(out.result.reasonMeta.missingContextKey, "sql.connection.catalogDb.host");
});

test("executeSqlExternalVerification rejects JDBC configuration explicitly", async () => {
  const out = await executeSqlExternalVerification({
    workspaceRootAbs: process.cwd(),
    resolvedContext: {
      "sql.connection.catalogDb.kind": "jdbc",
      "sql.connection.catalogDb.jdbc.url": "jdbc:postgresql://localhost:5432/catalog",
    },
    verification: {
      id: "verify_unsupported_jdbc",
      provider: { type: "sql" },
      request: {
        sql: {
          connectionRef: "catalogDb",
          statement: "SELECT 1",
        },
      },
      expect: [
        { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
      ],
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_connection_unsupported");
  assert.equal(out.result.reasonMeta.connectionKind, "jdbc");
});

test("executeSqlExternalVerification preserves deterministic PostgreSQL timeout failures", async () => {
  const out = await executeSqlExternalVerification({
    workspaceRootAbs: process.cwd(),
    resolvedContext: {
      "sql.connection.catalogDb.kind": "postgresql",
      "sql.connection.catalogDb.host": "db.internal",
      "sql.connection.catalogDb.port": 5432,
      "sql.connection.catalogDb.database": "catalog",
      "sql.connection.catalogDb.username": "catalog_user",
      "sql.connection.catalogDb.password": "catalog_pass",
      "sql.connection.catalogDb.tls.mode": "disable",
    },
    verification: {
      id: "verify_postgresql_timeout",
      provider: { type: "sql" },
      request: {
        sql: {
          connectionRef: "catalogDb",
          statement: "SELECT 1",
          timeoutMs: 10,
        },
      },
      expect: [
        { id: "row_count_ok", actualPath: "sql.rowCount", operator: "numeric_gte", expected: 1 },
      ],
    },
    internals: {
      executePostgresqlQuery: async ({ timeoutMs }: { timeoutMs?: number | null }) =>
        await new Promise((resolve) => {
          setTimeout(() => {
            resolve({
              ok: false,
              errorMessage: "sql_execution_timeout",
              durationMs: timeoutMs ?? 0,
            });
          }, 1);
        }),
    },
  });

  assert.equal(out.result.status, "blocked_runtime");
  assert.equal(out.result.reasonCode, "external_verification_execution_failed");
  assert.equal(out.result.reasonMeta.timeoutMs, 10);
  assert.equal(out.result.reasonMeta.cause, "sql_execution_timeout");
});
