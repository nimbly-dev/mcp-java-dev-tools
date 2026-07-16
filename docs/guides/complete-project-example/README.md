# Complete MCP-first project example

This guide is a copyable, schema-validated reference for a project with regression and performance plans. Replace only the documented placeholders; do not add credentials, tokens, JDBC URLs, driver names, or machine-specific absolute paths to persisted Artifacts.

## Layout

```text
<workspace>/.mcpjvm/probe-config.json
<workspace>/.mcpjvm/example-project/projects.json
<workspace>/.mcpjvm/example-project/plans/regression/producer/{metadata,contract}.json
<workspace>/.mcpjvm/example-project/plans/regression/consumer/{metadata,contract}.json
<workspace>/.mcpjvm/example-project/plans/performance/load-profile/{metadata,contract}.json
```

The files in `.mcpjvm/` are the canonical project and plan Artifacts. A maintained plan package also includes its matching `plan.md` beside `metadata.json` and `contract.json`. The files in `examples/` are focused variants for provider-specific or mutually exclusive configuration; they are not one combined project Artifact.

## Project context

Use [`.mcpjvm/probe-config.json`](.mcpjvm/probe-config.json) for probe registry selection and [`.mcpjvm/example-project/projects.json`](.mcpjvm/example-project/projects.json) for workspace-owned runtime contexts, external systems, scripts, and execution profiles. `projectRoot` is a placeholder and must be replaced with the actual workspace root before an execution profile is run.

Native PostgreSQL bindings use `sql.connection.<connectionRef>.*` with a resolved literal `kind: "postgresql"`. In `projects.json`, `variables.contextBindings` values are environment-key names by contract, so set `EXAMPLE_SQL_KIND=postgresql` in the project-owned environment source; the resolved runtime context then contains the required literal. The project Artifact contains no password or connection string. JDBC fields, JDBC URLs, and driver/classpath configuration are unsupported for this PostgreSQL provider and must not be used.

## Regression flow

The `producer` plan executes an ordered POST and extracts `eventId` with `scope: "suite"` and `secret: false`. Suite promotion is explicit and only successful, non-secret values can cross into the later `consumer` plan. The consumer performs a GET, waits for an eventual indexed field, and verifies downstream PostgreSQL state with a parameter bound from the derived value.

The `json_path` correlation variant demonstrates dynamic correlation-key derivation. Correlation evidence is separate from Watcher readiness: use a Watcher for bounded eventual state and correlation expectations for method-flow evidence.

## Performance flow

The `load-profile` plan uses the supported `concurrency` load model, one HTTP entrypoint, one required Strict Line Key, and deterministic throughput, error-rate, and p95 latency thresholds. The workload provider is `builtin`; use the JMeter variant only when the runtime has an approved JMeter installation.

## Scripts and secrets

[`examples/preplan-token-refresh.md`](examples/preplan-token-refresh.md) shows how a project-owned prePlan script can refresh a token without persisting its value. Secret prerequisites are resolved from the declared project-owned source and are redacted from plan-run Artifacts, SQLite summaries, MCP output, and logs.

Focused variants: [`postgres-context-bindings.json`](examples/postgres-context-bindings.json), [`watcher-eventual-field.json`](examples/watcher-eventual-field.json), [`suite-context-cross-plan.json`](examples/suite-context-cross-plan.json), [`correlation-json-path.json`](examples/correlation-json-path.json), and [`preplan-token-refresh.md`](examples/preplan-token-refresh.md).

## State-store operations

For an existing project, preserve and validate `projects.json`, then use the MCP-first `artifact_management` sequence:

1. `run_result` `rebuild` from canonical run Artifacts.
2. `correlation_state` `backfill` or correlation-only reconciliation when legacy correlation evidence exists.
3. `run_state` `cutover` only after rebuild and readiness checks pass; active suites are rejected.
4. Query `run_state`, `correlation_state`, and `watcher_state` through their bounded selectors.
5. Run retention `cleanup` as dry-run first, then apply in bounded batches. Cleanup excludes active suites, active Watchers, unexpired leases, resumable state, unsafe links, and missing canonical links.

After cutover, mutable orchestration state comes from SQLite and successful orchestration responses use `{ "suiteRunId": "...", "stateSurface": "run_state" }` without `statusArtifactPath`. Canonical plan-run Artifacts remain immutable evidence. Resume uses the same `suiteRunId`, continues the active phase, and does not resend completed triggers.

## Validation

The targeted validator is `test/tools/docs/complete-project-example.test.ts`. It parses every JSON file, validates the project Artifact, validates regression metadata/contracts with the current preflight validators, validates the performance contract with the current performance parser, checks placeholder safety, and checks that the documentation references every required example.
