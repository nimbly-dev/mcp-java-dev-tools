# MCP Query Playbook

Mandatory reads:

1. Validate a plan:

```json
{"artifactType":"regression_plan","action":"validate","input":{"projectName":"<project>","planName":"<plan>"}}
```

2. Read a plan summary and bounded sections. Repeat section windows until `returned + offset >= total`:

```json
{"artifactType":"regression_plan","action":"read","input":{"projectName":"<project>","planName":"<plan>","query":{"select":["summary","targets","prerequisites","steps"],"prerequisites":{"offset":0,"limit":50},"steps":{"offset":0,"limit":25}}}}
```

3. Read project context with explicit selection:

```json
{"artifactType":"project_context","action":"read","input":{"projectName":"<project>","query":{"select":["artifact","executionProfiles","runtimeContexts"]}}}
```

Use `query.executionProfile` when selecting one execution profile.

4. Read the selected canonical run Artifact. For `planName + runId`, pass both fields; do not read a run by filesystem path:

```json
{"artifactType":"run_result","action":"read","input":{"projectName":"<project>","planName":"<plan>","runId":"<run_id>","query":{"select":["summary","executionResult","evidence"]}}}
```

5. Resolve `stateQuery` and inspect run-state with bounded pagination:

```json
{"artifactType":"run_result","action":"query","input":{"projectName":"<project>","stateSurface":"run_state","query":{"suiteRunId":"<suite_run_id>","pageSize":10,"sortDirection":"desc"}}}
```

For `stateQuery`, preserve the caller's bounded filters and require exactly one returned execution before reading its canonical run Artifact. `planName + runId` and `suiteRunId` must be translated to equivalent bounded filters; never query all history.

Conditional reads:

- `stateSurface=watcher_state` for Watcher checkpoint detail;
- `stateSurface=correlation_state` for Correlation projection detail;
- bounded external-verification detail when its result is not sufficient in the run Artifact;
- execution-profile context when the run belongs to an execution profile or policy explains the result.

Conditional state detail uses the same `run_result` `query` envelope and sets `stateSurface` explicitly. Add only the needed bounded detail selector, such as `query.select=["watchers"]` with `query.watchers={"offset":0,"limit":20}`, or `query.detail` for Correlation/external-verification summaries. Never invoke `rebuild`, `backfill`, `cutover`, or `cleanup`.

For current readiness only, call the existing `probe` MCP Tool with:

```json
{
  "action": "status",
  "input": {
    "probeId": "<configured-probe-id>",
    "key": "fully.qualified.Class#method:line",
    "timeoutMs": 5000
  }
}
```

`probe.status` is read-only. Require `probeId` and a valid Strict Line Key when checking a configured target. Clamp the timeout to the Probe Tool's supported bounds; map unavailable and timeout outcomes to `diagnostic_runtime_unavailable` and `diagnostic_runtime_timeout`, preserving the underlying Probe reason code.
