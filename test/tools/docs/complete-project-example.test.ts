const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const { validateProjectArtifact } = require("@tools-project-artifact-spec/project_artifact.util");
const {
  parsePerformanceContract,
} = require("../../../tools/features/performance-suite/support/parse_performance_contract");
const {
  validateCorrelationPolicy,
  validateStepConditions,
  validateStepExpectations,
  validateSuiteContextDependencies,
  validateTransportPlaceholderSyntax,
} = require("../../../tools/features/regression-suite/support/regression_plan_preflight_validation");
const {
  validateStepExtracts,
} = require("../../../tools/features/regression-suite/shared/regression_step_extract");
const {
  validateExternalVerificationContract,
} = require("@tools-regression-execution-plan-spec/external_verification_contract.util");
const { loadProbeRegistry } = require("../../../tools/core/tools-core/src/probe-registry");
const {
  parsePerformancePlanMetadata,
} = require("../../../tools/features/performance-suite/support/parse_performance_plan_metadata");

const guideRoot = path.resolve(__dirname, "../../../docs/guides/complete-project-example");
const projectRoot = path.join(guideRoot, ".mcpjvm", "example-project");

function collectJsonFiles(root: string): string[] {
  const files: string[] = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const filePath = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...collectJsonFiles(filePath));
    else if (entry.isFile() && entry.name.endsWith(".json")) files.push(filePath);
  }
  return files;
}

function readJson(filePath: string): Record<string, unknown> {
  return JSON.parse(fs.readFileSync(filePath, "utf8")) as Record<string, unknown>;
}

function assertOk(result: {
  ok: boolean;
  reasonCode?: string;
  requiredUserAction?: string[];
}): void {
  assert.equal(result.ok, true, JSON.stringify(result));
}

function validateRegressionContract(contract: any): void {
  assertOk(validateStepExtracts(contract.steps));
  assertOk(validateStepExpectations(contract.steps));
  assertOk(validateStepConditions(contract.steps));
  assertOk(validateTransportPlaceholderSyntax(contract.steps));
  assertOk(validateCorrelationPolicy(contract.correlation));
  assertOk(validateExternalVerificationContract(contract.externalVerification));
}

test("complete project guide contains parseable, schema-validated examples", () => {
  const jsonFiles = collectJsonFiles(guideRoot);
  assert.equal(jsonFiles.length, 12);
  for (const filePath of jsonFiles) {
    assert.doesNotThrow(() => readJson(filePath), filePath);
    const text = fs.readFileSync(filePath, "utf8");
    assert.equal(/jdbc|jdbcUrl|driverClass/.test(text), false, filePath);
    assert.equal(/C:\\\\Users\\|\/Users\//.test(text), false, filePath);
  }

  const projectValidation = validateProjectArtifact(
    readJson(path.join(projectRoot, "projects.json")),
  );
  assertOk(projectValidation);
  assertOk(
    validateProjectArtifact(
      readJson(path.join(guideRoot, "examples", "postgres-context-bindings.json")),
    ),
  );
  const contextBindings = (readJson(path.join(projectRoot, "projects.json")).workspaces as any[])[0]
    ?.variables?.contextBindings as Record<string, string>;
  assert.equal(contextBindings?.["sql.connection.analytics.kind"], "postgresql");
  for (const key of [
    "sql.connection.analytics.kind",
    "sql.connection.analytics.host",
    "sql.connection.analytics.port",
    "sql.connection.analytics.database",
    "sql.connection.analytics.username",
    "sql.connection.analytics.password",
    "sql.connection.analytics.tls.mode",
  ]) {
    assert.equal(typeof contextBindings?.[key], "string", key);
  }
  assert.equal(contextBindings?.["sql.connection.analytics.tls.mode"], "EXAMPLE_SQL_TLS_MODE");
  assert.equal("sql.connection.analytics.user" in contextBindings, false);

  const probeRegistry = loadProbeRegistry({
    filePath: path.join(guideRoot, ".mcpjvm", "probe-config.json"),
    workspaceRootAbs: guideRoot,
  });
  assert.equal(probeRegistry.activeProfile, "local");
  assert.equal(probeRegistry.probesById.has("example-service"), true);

  const planRoot = path.join(projectRoot, "plans");
  const regressionPlans = ["producer", "consumer"].map((planName) => {
    const root = path.join(planRoot, "regression", planName);
    const metadata = readJson(path.join(root, "metadata.json"));
    const contract = readJson(path.join(root, "contract.json")) as any;
    assert.equal((metadata.execution as Record<string, unknown>).intent, "regression");
    validateRegressionContract(contract);
    return { planName, contract };
  });
  assertOk(validateSuiteContextDependencies({ plans: regressionPlans }));

  for (const name of [
    "watcher-eventual-field.json",
    "suite-context-cross-plan.json",
    "correlation-json-path.json",
  ]) {
    validateRegressionContract(readJson(path.join(guideRoot, "examples", name)));
  }

  const performanceRoot = path.join(planRoot, "performance", "load-profile");
  const performanceMetadata = readJson(path.join(performanceRoot, "metadata.json"));
  assertOk(parsePerformancePlanMetadata(performanceMetadata));
  const performance = parsePerformanceContract(
    readJson(path.join(performanceRoot, "contract.json")),
  );
  assertOk(performance);
});

test("complete project guide documents every required focused variant", () => {
  const readme = fs.readFileSync(path.join(guideRoot, "README.md"), "utf8");
  for (const name of [
    "postgres-context-bindings.json",
    "watcher-eventual-field.json",
    "suite-context-cross-plan.json",
    "correlation-json-path.json",
    "preplan-token-refresh.md",
  ]) {
    assert.match(readme, new RegExp(name.replace(/[.*+?^${}()|[\\]\\]/g, "\\$&")));
  }
});
