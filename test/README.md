# Test Layout

Centralized test assets live under the top-level `test` tree. Unit tests are
organized by the TypeScript owner they exercise; integration tests are
organized by product owner, externally exercised boundary, action, fixture,
and scenario.

## Structure

```text
test/
|- unit/
|  |- contracts/
|  |- core/
|  |- features/
|  |- spec/
|  |- synthesizers/
|  |- transport/
|  |- skills/
|  \- docs/
|- fixtures/
|  \- spring-apps/
\- integration/
   |- transport/
   \- features/
```

## Intent

- `fixtures/spring-apps` contains real Spring fixture projects used only for integration testing.
- `unit` contains focused tests organized by Contract, Core, Feature Module,
  Artifact Spec, Synthesizer, Transport, Skill, or documentation owner.
- `fixtures/spring-apps` contains real Spring fixture projects used only for integration testing.
- `integration` contains cross-module tests that exercise:
  - TS orchestration
  - Java request-mapping synthesis
  - MCP tool execution against a live probe runtime
  - probe runtime behavior

Integration tests are intentionally outside `unit` because they validate the
integrated toolchain rather than a single module in isolation.
