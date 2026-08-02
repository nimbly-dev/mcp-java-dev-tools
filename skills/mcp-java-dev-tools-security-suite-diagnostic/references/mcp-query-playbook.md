# Bounded MCP query playbook

Use the repository's existing MCP Tool contracts. The JSON below is illustrative input shape; preserve the installed tool's exact validation and output contract. Keep every read bounded by project, plan, run, suite, and small page-size filters.

## Plan validation

```json
{"artifactType":"security_plan","action":"validate","input":{"projectName":"<project>","planName":"<plan>"}}
```

If validation succeeds, perform a bounded read for only the metadata/contract fields needed to explain readiness. Do not print the contract or secrets in the report.

```json
{"artifactType":"security_plan","action":"read","input":{"projectName":"<project>","planName":"<plan>","query":{"select":["summary","metadata","contract"]}}}
```

## Project context

```json
{"artifactType":"project_context","action":"read","input":{"projectName":"<project>","query":{"select":["artifact","executionProfiles","runtimeContexts"],"executionProfile":"<profile>"}}}
```

Use only the selected profile and the fields needed to explain a missing or incompatible runtime/target dependency.

## Canonical run result

For an explicit `planName` and `runId`, query one run and request only bounded summary fields:

```json
{"artifactType":"run_result","action":"read","input":{"projectName":"<project>","planName":"<plan>","runId":"<run_id>","query":{"select":["summary","executionResult","matrix","coverage","findings","evidence"]}}}
```

Use the installed `run_result` contract if selection names differ. Do not read an unbounded directory or infer the run from filesystem ordering.

## State lookup

For a `suiteRunId` or a bounded state query:

```json
{"artifactType":"run_result","action":"query","input":{"projectName":"<project>","stateSurface":"run_state","query":{"suiteType":"security","suiteRunId":"<suite_run_id>","pageSize":10,"sortDirection":"desc"}}}
```

If more than one candidate remains, report `security_diagnostic_run_ambiguous` and stop. Do not automatically select the newest candidate.

## Optional current Probe check

Only for a Sidecar-assisted plan and only after loading historical evidence:

```json
{"action":"status","input":{"projectName":"<project>","runtimeContext":"<runtime_context>","strictLineKey":"<safe_line_key>"}}
```

Use the installed Probe contract and omit the check when no runtime evidence is required. Report current status separately from historical case evidence.

## Forbidden operations

Do not call Security Suite execute/resume actions, plan upsert, Artifact writes, SQLite rebuild/backfill/cutover/retention/cleanup actions, or any operation that changes execution state. If the user requests one, return the read-only boundary and a single safe next action.
