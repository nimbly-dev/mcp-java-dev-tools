# TS Tools Structure

This folder is the workspace-style scaffold for TS tool packages.

## Package groups
- `contracts/tools-contracts`
- `core/tools-core`
- `core/tools-registry`
- `features/execution-profile-export`
- `spec/project-artifact-spec`
- `spec/regression-execution-plan-spec`
- `synthesizers/tools-synthesizer-api`
- `synthesizers/tools-spring-http`
- `synthesizers/tools-jaxrs-http`
- `synthesizers/tools-grpc-rpc`
- `transport/tools-mcp-server`

## Integrated test location
- Top-level fixture and integration scaffolding lives under:
  - `test/fixtures/spring-apps`
  - `test/integrations/{fixture_app}/{feature}/{tool_name}`

Cross-module MCP integration coverage belongs in the centralized top-level `test` tree, not under `tools/tests`.

## Opt-in examples
- `synthesizers/tools-synthesizer-example`

Example packages are starter scaffolds for framework adoptors and are not included in default runtime registration or TS build include paths.

## Placeholder packages
- `synthesizers/tools-jaxrs-http`
- `synthesizers/tools-grpc-rpc`

These are folder-level placeholders only. Spring HTTP is the only active synthesizer implementation today.

## Active runtime packages
- MCP transport/server entrypoint code:
  - `transport/tools-mcp-server/src`
- Shared recipe orchestration/runtime utilities:
  - `core/tools-core/src`
- Synthesizer plugin registry runtime:
  - `core/tools-registry/src`
- Execution Profile export feature implementation:
  - `features/execution-profile-export`
- `.mcpjvm/**` Artifact Spec implementations:
  - `spec/project-artifact-spec/src`
  - `spec/regression-execution-plan-spec/src` (models, validation contracts, and path policy only)
- Regression Suite runtime implementation:
  - `features/regression-suite`
- Spring HTTP synthesizer runtime implementation:
  - `synthesizers/tools-spring-http/src`

## Feature Module boundary conventions

- Every Feature Module has a public `index.ts` entrypoint.
- Action selection belongs in `actions/index.ts` as a typed dispatch map; it
  contains no product behavior.
- `shared/**` is Feature-private. A Feature Module must not import another
  Feature Module's `shared/**` path, and Transport Adapters must not import
  Feature-private code directly.
- Real multi-feature consumers have a named owner under `tools/core/**`,
  `tools/contracts/**`, or `tools/spec/**`.
- Anonymous global `tools/shared/**` and `utils/shared/**` directories are
  prohibited.

Run `npm run boundaries:check` to enforce these structural rules.

## Runtime External Synthesizer Loading
- Configure external plugin module specifiers with:
  - `MCP_SYNTHESIZER_PLUGIN_MODULES`
- Supports path-delimited or comma-separated module specifiers.
- Load behavior is strict fail-closed:
  - every configured module must load
  - module exports must provide valid synthesizer plugin contract objects
  - plugin API version must be compatible
  - any bootstrap failure returns deterministic synthesis `report` (`failedStep=plugin_bootstrap`)

