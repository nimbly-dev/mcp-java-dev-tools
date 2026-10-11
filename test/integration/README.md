# Integration Test Layout

Integration tests are organized by product owner, externally exercised
boundary, action, fixture, and scenario:

```text
test/integration/
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

The runnable MCP boundary is covered by the packaged Java STDIO integration
tests under `mcp-server/application`. Legacy TypeScript integration sources are
migration references only for #644; they are not executed as Java MCP acceptance
evidence. Any behavior retained from them must be implemented and exercised
through the Java server.

The integration runner remains serial (`--test-concurrency=1`) and discovers
`test/integration/**/*.it.ts`. Shared fixture lifecycle and reusable helpers
remain under `test/support/`.
