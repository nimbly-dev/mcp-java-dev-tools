# Integration Test Layout

Integration tests are organized by product owner, externally exercised
boundary, action, fixture, and scenario:

```text
test/integration/
  transport/tools-mcp-server/mcp/
  features/
    route-synthesis/mcp/create_recipe/spring-social-platform/
    artifact-management/mcp/{probe_config,project_context,regression_plan,run_result,execution_export}/
    execution-orchestration/mcp/
    probe/mcp/
    probe/java-agent/spring-social-platform/
    regression-suite/mcp/spring-social-platform/
    transport-execution/mcp/spring-social-platform/
test/support/
  spring/social-platform/
```

Path order is fixed: `integration -> owner -> boundary -> action -> fixture ->
scenario`. Fixture applications qualify the owner and boundary; they are not
top-level ownership buckets.

Test names use the compact positional format from #509. Outcome/status tags
such as `ok`, `blocked`, and `line_hit` are intentionally omitted; the
human-readable behavior text carries the result:

```text
[IT][tool][artifact?][action?] human-readable behavior
```

Use exact MCP Tool and action values when an integration crosses an MCP Tool
boundary. Direct Feature Module or fixture-boundary coverage identifies its
owning Feature Module instead of using a generic legacy bucket.

The integration runner remains serial (`--test-concurrency=1`) and discovers
`test/integration/**/*.it.ts`. Shared fixture lifecycle and reusable helpers
remain under `test/support/`.
