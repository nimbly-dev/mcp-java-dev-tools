#!/usr/bin/env node
// Read-only consolidation of the existing scenario evidence, never product run state.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const inventory = JSON.parse(fs.readFileSync(path.join(root, 'docs/acceptance/java-it-coverage.json'), 'utf8'));
const args = process.argv.slice(2);
const outputArg = args.indexOf('--output');
const output = path.resolve(root, outputArg < 0 ? 'test/.tmp/656-coverage-report.json' : args[outputArg + 1]);
const temporary = path.join(root, 'test/.tmp');
if (!output.startsWith(temporary + path.sep)) throw new Error('Evidence output must be beneath repository test/.tmp');
const problems = [];
const operations = inventory.operations.map(row => ({ ...inventory.scenarioGroups[row.scenarioGroup], ...row }));
const supported = new Set(operations.map(row => row.operationId));
const scenarioIds = new Set(inventory.scenarios.map(row => row.id));
function read(relative) { return fs.readFileSync(path.join(root, relative), 'utf8'); }
function walk(relative) {
  return fs.readdirSync(path.join(root, relative), { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? walk(`${relative}/${entry.name}`) : [`${relative}/${entry.name}`]);
}
function requireThat(condition, message) { if (!condition) problems.push(message); }
requireThat(supported.size === inventory.operations.length, 'duplicate operation mapping');
const manifest = 'mcp-server/core/src/main/resources/META-INF/mcpjvm/operations';
const declared = [];
for (const entry of read(`${manifest}/index.xml`).matchAll(/<resource name="([^"]+)"/g)) {
  const xml = read(`${manifest}/${entry[1]}`);
  const api = xml.match(/<tool name="([^"]+)"/)[1];
  for (const match of xml.matchAll(/<action ([^>]+)>/g)) {
    const fields = Object.fromEntries([...match[1].matchAll(/(\w+)="([^"]+)"/g)].map(m => [m[1], m[2]]));
    declared.push([api, fields.subject, fields.name].filter(Boolean).join('.'));
  }
}
requireThat(declared.length === supported.size && declared.every(id => supported.has(id)), 'manifest operations differ from inventory');
for (const row of operations) {
  requireThat(row.scenarios.length > 0 && row.scenarios.every(id => scenarioIds.has(id)), `${row.operationId}: unmapped scenario`);
  requireThat(fs.existsSync(path.join(root, row.behaviorInventory)), `${row.operationId}: missing behavior inventory`);
  requireThat(['covered', 'partially covered', 'missing', 'intentionally retired'].includes(row.disposition), `${row.operationId}: invalid disposition`);
}
for (const row of inventory.scenarios) {
  requireThat(read(row.source).includes(`void ${row.id.split('.').at(-1)}(`), `${row.id}: missing test method`);
}
const actualSkills = walk('skills').filter(file => file.endsWith('/SKILL.md')).sort();
requireThat(JSON.stringify(actualSkills) === JSON.stringify(inventory.skills.map(s => s.path).sort()), 'added/unmapped Skill');
for (const skill of inventory.skills) {
  const directory = path.posix.dirname(skill.path);
  const dependencies = walk(directory).filter(file => !file.includes('/agents/')).sort();
  requireThat(JSON.stringify(dependencies) === JSON.stringify([...skill.dependencies].sort()), `${skill.name}: dependency inventory drift`);
  requireThat(skill.sequence.every(id => supported.has(id) || id === 'local_sanitized_evidence_to_markdown_no_publication'), `${skill.name}: unsupported operation`);
}

const evidence = new Map();
if (fs.existsSync(temporary)) {
  for (const run of fs.readdirSync(temporary, { withFileTypes: true }).filter(e => e.isDirectory() && e.name.startsWith('java-it-'))) {
    const base = path.join(temporary, run.name);
    for (const scenario of fs.readdirSync(base, { withFileTypes: true }).filter(e => e.isDirectory() && /^s-\d+$/.test(e.name))) {
      const file = path.join(base, scenario.name, 'evidence.json');
      if (!fs.existsSync(file)) continue;
      try {
        const item = JSON.parse(fs.readFileSync(file, 'utf8'));
        // Only the support's bounded allowlist is consumed; no raw outputs or product payloads.
        const record = { scenarioId: item.scenarioId, outcome: item.outcome, cleanupSucceeded: item.cleanupSucceeded,
          time: item.time, javaVersion: item.javaVersion, os: item.os,
          evidence: path.relative(root, file).split(path.sep).join('/'), tags: item.tags };
        const existing = evidence.get(item.scenarioId) ?? [];
        existing.push(record);
        evidence.set(item.scenarioId, existing);
      } catch {
        problems.push(`invalid evidence: ${path.relative(root, file)}`);
      }
    }
  }
}
const scenarios = inventory.scenarios.map(row => {
  const records = (evidence.get(row.id) ?? []).sort((a, b) => b.time.localeCompare(a.time));
  const passed = records.find(e => e.outcome === 'passed' && e.cleanupSucceeded === true);
  return { ...row, scenarioId: row.id,
    applicableOnCurrentHost: !row.platforms || row.platforms.includes(process.platform),
    command: inventory.scenarioContract.commandTemplate.replace('<selector>', row.selector),
    cleanupContract: inventory.scenarioContract.cleanup,
    disposition: passed ? 'covered' : 'missing',
    basis: passed ? 'observed prior successful scenario; capability document owns variants and reuse applicability' : 'no successful local evidence',
    latestAttempt: records[0] ?? null, successfulEvidence: passed ?? null,
    sourceSha256: crypto.createHash('sha256').update(read(row.source)).digest('hex') };
});
const workflowIds = new Set(inventory.workflowScenarios);
const workflows = scenarios.filter(s => workflowIds.has(s.scenarioId));
const requiredMissing = workflows.filter(s => s.disposition !== 'covered'
  || s.latestAttempt?.outcome !== 'passed' || s.latestAttempt?.cleanupSucceeded !== true);
const report = { ticket: 656, generatedAt: new Date().toISOString(), inventoryValid: problems.length === 0,
  claim: 'Consolidated Tool/Artifact evidence. Prior successful evidence does not imply freshness after source/input/environment changes.',
  actualSkillUse: 'not_exercised', problems,
  counts: { operations: supported.size, skills: inventory.skills.length, scenarios: scenarios.length,
    observedSuccessful: scenarios.filter(s => s.disposition === 'covered').length, workflowScenarios: workflows.length,
    notApplicableOnCurrentHost: scenarios.filter(s => !s.applicableOnCurrentHost).length },
  workflowAcceptance: requiredMissing.length ? 'blocked' : 'evidence_present_requires_provenance_check',
  requiredMissing: requiredMissing.map(s => s.scenarioId), operations, scenarios,
  skills: inventory.skills.map(s => ({ name: s.name, sequence: s.sequence, directArtifactDependencies: s.directArtifactDependencies,
    dependencies: s.dependencies, actualSkillUse: s.actualSkillUse, limitations: s.limitations })) };
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ inventoryValid: report.inventoryValid, counts: report.counts,
  workflowAcceptance: report.workflowAcceptance, requiredMissing: report.requiredMissing,
  output: path.relative(root, output).split(path.sep).join('/') }));
if (problems.length || (args.includes('--require-workflow-evidence') && requiredMissing.length)) process.exitCode = 1;
