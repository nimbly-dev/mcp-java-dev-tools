# Docs Index

## Specs

- Source of truth: `docs/specs/README.md`

## Data Fields

- Source of truth: `docs/data-fields/README.md`
- Coverage test: `test/docs/data-fields-docs.test.ts`

The `data-fields` README is expected to contain one section per MCP tool registered in `tools/transport/tools-mcp-server/src/server.ts`.

## Java MCP Architecture

- Tracked contract amendment: `docs/architecture/java-mcp-catalog-describe-execute-contract.md`
- Reference implementation: `docs/architecture/java-mcp-operation-catalog.md`

The local `.dev/agent-contract` pack remains the canonical session-operating
contract. The tracked document is the repository-delivered Java MCP
architecture amendment for migrated Catalog-Describe-Execute capabilities;
both apply when a Java MCP change is in scope, and local guidance cannot waive
the tracked amendment.
