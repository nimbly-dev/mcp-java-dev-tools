# Test layout

Java tests live with their owning Maven modules under `java-agent` and
`mcp-server`. Repository fixtures remain under `test/fixtures`; generated test
workspaces and evidence stay under the ignored `test/.tmp` directory.

The surviving `test/unit/skills/*.test.cjs` and `test/unit/docs/*.test.cjs`
assert Skill guidance, portable bundled resources, CDE example boundaries, and
documentation data. Run them from the repository root with Node:

```text
node scripts/run-unit-tests.cjs
```

No npm install, TypeScript compiler, or package manifest is required. Java Core
tests validate bundled crafter templates and guide Artifacts using their
current Java owners. Dedicated resource assertions retain step, extraction,
external-verification, placeholder, suite-promotion, correlation, and Performance
load/threshold semantics. These independent sections do not establish executable
plan readiness: the guide's incomplete correlation shape and oversized Watcher
timeout still need separate template repair. Performance validation fills only
illustrative request values and stops at an in-process health-check stub.

For affected Java modules, run `mvn -pl <module> -am verify` from the owning
reactor. The [Java MCP README](../mcp-server/README.md) describes the existing
packaged STDIO integration selections, fixture prerequisites, and the retained
`mcp-server/test-support/java-it-coverage.mjs` inventory validator. The six
shipped regression Skill helpers remain under
`skills/mcp-java-dev-tools-regression-suite/scripts` and are exercised by
`McpServerSkillWorkflowIT.repositoryHelpersPreserveBlockedFailedAndCleanupBoundaries`.
