const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { buildReplayPreflight } = require("@tools-regression-execution-plan-spec/regression_execution_plan_spec.util");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function loadCrafterTemplates() {
  const skillDir = path.join(process.cwd(), "skills", "mcp-java-dev-tools-regression-plan-crafter");
  const metadataTemplate = JSON.parse(
    readUtf8(path.join(skillDir, "references", "templates", "metadata.template.json")),
  );
  const contractTemplate = JSON.parse(
    readUtf8(path.join(skillDir, "references", "templates", "contract.template.json")),
  );
  const planTemplate = readUtf8(path.join(skillDir, "references", "templates", "plan.template.md"));
  return { skillDir, metadataTemplate, contractTemplate, planTemplate };
}

test("regression plan crafter skill is portable with bundled references and templates", () => {
  const { skillDir } = loadCrafterTemplates();
  const skill = readUtf8(path.join(skillDir, "SKILL.md"));
  assert.match(skill, /Portable Source of Truth/);
  assert.ok(fs.existsSync(path.join(skillDir, "references", "spec-rules.md")));
  assert.ok(fs.existsSync(path.join(skillDir, "references", "authoring-checklist.md")));
  assert.ok(fs.existsSync(path.join(skillDir, "references", "templates", "metadata.template.json")));
  assert.ok(fs.existsSync(path.join(skillDir, "references", "templates", "contract.template.json")));
  assert.ok(fs.existsSync(path.join(skillDir, "references", "templates", "plan.template.md")));
});

test("bundled crafter templates can bootstrap a plan package inside test/.tmp workspace", () => {
  const tmpRoot = createTestTempDir("regression-plan-crafter");
  try {
    const { metadataTemplate, contractTemplate, planTemplate } = loadCrafterTemplates();
    const regressionName = "post-lifecycle-smoke";
    const projectName = "test-project";
    const targetDir = path.join(tmpRoot, ".mcpjvm", projectName, "plans", "regression", regressionName);
    fs.mkdirSync(targetDir, { recursive: true });

    metadataTemplate.specVersion = "1.0.0";
    contractTemplate.targets[0].selectors.fqcn = "com.example.social.post.app.controller.PostController";
    contractTemplate.targets[0].selectors.method = "createPost";
    contractTemplate.prerequisites[0].default = "tenant-social-001";

    fs.writeFileSync(path.join(targetDir, "metadata.json"), `${JSON.stringify(metadataTemplate, null, 2)}\n`);
    fs.writeFileSync(path.join(targetDir, "contract.json"), `${JSON.stringify(contractTemplate, null, 2)}\n`);
    fs.writeFileSync(path.join(targetDir, "plan.md"), planTemplate.replaceAll("<regression_name>", regressionName));

    assert.ok(fs.existsSync(path.join(targetDir, "metadata.json")));
    assert.ok(fs.existsSync(path.join(targetDir, "contract.json")));
    assert.ok(fs.existsSync(path.join(targetDir, "plan.md")));

    const metadata = JSON.parse(readUtf8(path.join(targetDir, "metadata.json")));
    const contract = JSON.parse(readUtf8(path.join(targetDir, "contract.json")));
    const plan = readUtf8(path.join(targetDir, "plan.md"));

    assert.equal(metadata.execution.intent, "regression");
    assert.equal(contract.steps[0].protocol, "http");
    assert.ok(contract.steps[0].transport.http);
    assert.match(plan, /^# Purpose/m);
    assert.match(plan, /^# Steps/m);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("crafted template payload passes deterministic preflight schema checks", () => {
  const { metadataTemplate, contractTemplate } = loadCrafterTemplates();
  const preflight = buildReplayPreflight({
    metadata: metadataTemplate,
    contract: contractTemplate,
    providedContext: { "auth.bearer": "runtime-token" },
    targetCandidateCount: 1,
  });
  assert.equal(preflight.status, "ready");
  assert.equal(preflight.reasonCode, "ok");
});

test("crafted template marks secret prerequisites without persisted defaults", () => {
  const { contractTemplate } = loadCrafterTemplates();
  const secretKeysWithDefaults = contractTemplate.prerequisites
    .filter((entry: { key: string; secret: boolean; default?: unknown }) => entry.secret)
    .filter((entry: { key: string; secret: boolean; default?: unknown }) => typeof entry.default !== "undefined")
    .map((entry: { key: string }) => entry.key);
  assert.deepEqual(secretKeysWithDefaults, []);
});

test("regression suite skill remains execution-focused and result skill is available separately", () => {
  const suitePath = path.join(process.cwd(), "skills", "mcp-java-dev-tools-regression-suite", "SKILL.md");
  const resultPath = path.join(process.cwd(), "skills", "mcp-java-dev-tools-regression-result", "SKILL.md");

  const suiteText = readUtf8(suitePath);
  const resultText = readUtf8(resultPath);

  assert.match(suiteText, /Single-Call Execution Contract/);
  assert.match(suiteText, /\.mcpjvm\/<project_name>\/plans\/regression\/<plan>\/runs\/<run_id>/);
  assert.match(suiteText, /correlation\.json/);
  assert.match(suiteText, /correlation-index\.json/);
  assert.match(suiteText, /durationMs/);
  assert.match(suiteText, /correlationPolicy/);
  assert.match(suiteText, /correlationEvents/);
  assert.match(suiteText, /do not author `correlation\.json` directly/i);
  assert.match(suiteText, /artifact writer flow/i);
  assert.match(suiteText, /Discovery-First Orchestration/);
  assert.match(suiteText, /Wrapper script usage is optional implementation detail/i);
  assert.match(suiteText, /Context and Read Budget/);
  assert.match(suiteText, /Always use bounded or windowed reads for Artifact inspection, logs, and generated scripts/i);
  assert.match(suiteText, /Do not switch to full Artifact reads based on artifact size/i);
  assert.match(suiteText, /treat `prerequisites` as windowable/i);
  assert.match(suiteText, /treat `steps` as windowable/i);
  assert.match(suiteText, /load each ordered plan through `artifact_management` .* explicit pagination/i);
  assert.match(suiteText, /do not read `contract\.json`, `metadata\.json`, or `plan\.md` directly/i);
  assert.match(suiteText, /the orchestrator should load runtime suite plan inputs through these paged `artifact_management` calls/i);
  assert.match(suiteText, /`artifact_management` is the maintained read path for execution profile lookup and regression plan loading/i);
  assert.match(suiteText, /maxPlansPerCall/);
  assert.match(suiteText, /status="in_progress"/);
  assert.match(suiteText, /suiteRunId/);
  assert.match(suiteText, /MUST use resumable orchestration slices/i);
  assert.match(suiteText, /Do not summarize a runtime-suite `execution_profile` run as completed until the terminal orchestration status is returned/i);
  assert.match(suiteText, /Do not treat a caller\/tool-boundary timeout as the primary execution result/i);
  assert.match(suiteText, /Do not reuse a stale suite artifact as the result of a fresh execution request/i);
  assert.match(suiteText, /Select-Object -First/);
  assert.match(suiteText, /external_healthcheck_failed/);
  assert.match(suiteText, /--probe-id <id>/);
  assert.match(suiteText, /--agent-port <port>/);
  assert.match(suiteText, /Do not rely on auto-scanned probe port/i);
  assert.match(resultText, /default template: `endpoint_table_result`/);
  assert.match(resultText, /references\/templates\/index\.md/);
});
