const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
// Artifact validation uses current Java owners in RepositoryArtifactResourcesTest.
const guideRoot = path.resolve(__dirname, "../../../docs/guides/complete-project-example");
const projectRoot = path.join(guideRoot, ".mcpjvm", "example-project");
function collectJsonFiles(root) {
    const files = [];
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
        const filePath = path.join(root, entry.name);
        if (entry.isDirectory())
            files.push(...collectJsonFiles(filePath));
        else if (entry.isFile() && entry.name.endsWith(".json"))
            files.push(filePath);
    }
    return files;
}
function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
}
test("[UT][docs][complete_project_example] complete project guide contains parseable, portable examples", () => {
    const jsonFiles = collectJsonFiles(guideRoot);
    assert.equal(jsonFiles.length, 12);
    for (const filePath of jsonFiles) {
        assert.doesNotThrow(() => readJson(filePath), filePath);
        const text = fs.readFileSync(filePath, "utf8");
        assert.equal(/jdbc|jdbcUrl|driverClass/.test(text), false, filePath);
        assert.equal(/C:\\\\Users\\|\/Users\//.test(text), false, filePath);
    }
    const contextBindings = readJson(path.join(projectRoot, "projects.json")).workspaces[0]
        ?.variables?.contextBindings;
    assert.equal(contextBindings?.["sql.connection.analytics.kind"], "EXAMPLE_SQL_KIND");
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
    const probeRegistry = readJson(path.join(guideRoot, '.mcpjvm', 'probe-config.json'));
    assert.equal(probeRegistry.defaultProfile, 'local');
    assert.ok(probeRegistry.profiles.local.probes['example-service']);
});
test("[UT][docs][complete_project_example] complete project guide documents every required focused variant", () => {
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

// These resource assertions complement Java semantic preflight. They cover the
// guide's authoring examples and suite references without claiming runtime readiness.
const regressionResourcePaths = [
  path.join(projectRoot, 'plans/regression/producer/contract.json'),
  path.join(projectRoot, 'plans/regression/consumer/contract.json'),
  ...['watcher-eventual-field', 'suite-context-cross-plan', 'correlation-json-path']
    .map(name => path.join(guideRoot, 'examples', `${name}.json`)),
];

function* stringValues(value) {
  if (typeof value === 'string') yield value;
  else if (value && typeof value === 'object') {
    for (const child of Object.values(value)) yield* stringValues(child);
  }
}

function placeholderKeys(value) {
  const keys = new Set();
  for (const text of stringValues(value)) {
    const normalized = text
      .replace(/\{\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}\}/g, (_, key) => '${' + key + '}')
      .replace(/\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g, (_, key) => '${' + key + '}');
    const tokens = /\$\{\s*([A-Za-z0-9_.-]+)\s*\}/g;
    for (const token of normalized.matchAll(tokens)) keys.add(token[1]);
    assert.doesNotMatch(normalized.replace(tokens, ''), /\$\{|\{\{|\}\}/,
      `Malformed transport placeholder in ${text}`);
  }
  return keys;
}

test('[UT][docs][complete_project_example] regression transport placeholders retain supported syntax', () => {
  for (const resource of regressionResourcePaths) {
    for (const step of readJson(resource).steps) placeholderKeys(step.transport);
  }
  assert.deepEqual([...placeholderKeys({ path: '${eventId}', header: '{{ auth.bearer }}' })],
    ['eventId', 'auth.bearer']);
  assert.throws(() => placeholderKeys({ path: '/imports/${eventId' }), /Malformed transport placeholder/);
});

function assertSuitePromotions(plans) {
  const promoted = new Set();
  for (const contract of plans) {
    const available = new Set([
      ...contract.prerequisites.map(entry => entry.key),
      ...contract.steps.flatMap(step => (step.extract ?? []).map(entry => entry.as)),
      ...promoted,
    ]);
    const references = placeholderKeys([
      ...contract.steps.map(step => step.transport),
      ...(contract.watchers ?? []).map(watcher => watcher.provider.transport),
      ...(contract.externalVerification ?? []).map(verification => verification.request),
    ]);
    for (const key of references) {
      assert.ok(available.has(key), `Suite context '${key}' must be promoted by an earlier plan`);
    }
    for (const step of contract.steps) {
      for (const extraction of step.extract ?? []) {
        if (extraction.scope === 'suite' && extraction.secret === false) promoted.add(extraction.as);
      }
    }
  }
}

test('[UT][docs][complete_project_example] suite consumers follow explicit non-secret producer promotion', () => {
  const plans = regressionResourcePaths.slice(0, 2).map(readJson);
  assertSuitePromotions(plans);
  assert.throws(() => assertSuitePromotions([...plans].reverse()), /must be promoted by an earlier plan/);
  plans[0].steps[0].extract[0].secret = true;
  assert.throws(() => assertSuitePromotions(plans), /must be promoted by an earlier plan/);
});

function assertCorrelationExample(correlation) {
  assert.equal(correlation.enabled, true);
  assert.ok(['traceId', 'requestId', 'messageId'].includes(correlation.key.type));
  assert.equal(correlation.key.source.type, 'json_path');
  assert.match(correlation.key.source.path,
    /^[A-Za-z_][A-Za-z0-9_-]*(\[\d+\])*(\.[A-Za-z_][A-Za-z0-9_-]*(\[\d+\])*)*$/);
  assert.ok(Number.isInteger(correlation.window.maxWindowMs) && correlation.window.maxWindowMs > 0);
  assert.equal(correlation.matchPolicy.requireExactKeyMatch, true);
  assert.equal(correlation.matchPolicy.requireWindowMatch, true);
  assert.equal(correlation.matchPolicy.ambiguityStrategy, 'fail_closed');
  const seen = new Set();
  assert.ok(correlation.strictLineExpectations.length > 0);
  for (const expectation of correlation.strictLineExpectations) {
    assert.match(expectation.strictLineKey, /^[\w.$]+#[\w$<>]+:\d+$/);
    assert.ok(Number.isInteger(expectation.sequenceOrder) && expectation.sequenceOrder > 0);
    assert.equal(expectation.selectorPolicy, 'exact_instance');
    assert.equal(expectation.operator, 'at_least');
    assert.ok(Number.isInteger(expectation.expectedHitDelta) && expectation.expectedHitDelta >= 0);
    const key = `${expectation.sequenceOrder}:${expectation.strictLineKey}`;
    assert.equal(seen.has(key), false, 'Duplicate correlation expectation');
    seen.add(key);
  }
}

test('[UT][docs][complete_project_example] correlation examples retain key, window, matching and Strict Line semantics', () => {
  for (const resource of [regressionResourcePaths[0], regressionResourcePaths[4]]) {
    const correlation = readJson(resource).correlation;
    assertCorrelationExample(correlation);
    correlation.strictLineExpectations[0].strictLineKey = 'not-a-strict-line-key';
    assert.throws(() => assertCorrelationExample(correlation));
  }
});

test('[UT][docs][complete_project_example] SQL verification keeps canonical parameter bindings and excludes inline credentials', () => {
  const consumer = readJson(regressionResourcePaths[1]);
  const bindings = readJson(path.join(projectRoot, 'projects.json')).workspaces[0].variables.contextBindings;
  for (const verification of consumer.externalVerification) {
    assert.equal(verification.provider.type, 'sql');
    const request = verification.request.sql;
    assert.ok(Object.keys(request).every(key => ['connectionRef', 'statement', 'parameters', 'timeoutMs'].includes(key)));
    assert.equal(typeof bindings[`sql.connection.${request.connectionRef}.kind`], 'string');
    assert.equal(typeof request.statement, 'string');
    assert.notEqual(request.statement.trim(), '');
    assert.deepEqual([...placeholderKeys(request.statement)], [], 'SQL uses bound parameters, not substitution');
    assert.ok(Number.isInteger(request.timeoutMs) && request.timeoutMs > 0);
    assert.ok(Array.isArray(request.parameters));
    for (const parameter of request.parameters) {
      assert.equal(typeof parameter.name, 'string');
      assert.notEqual(parameter.name.trim(), '');
      const fromContext = typeof parameter.valueFromContext === 'string' && parameter.valueFromContext.trim() !== '';
      assert.notEqual(Object.hasOwn(parameter, 'value'), fromContext, 'Exactly one parameter value source');
      if (fromContext) assert.doesNotMatch(parameter.valueFromContext, /^context\./);
    }
  }
});
