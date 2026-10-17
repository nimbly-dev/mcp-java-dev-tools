# Integration tests

The packaged Java STDIO integration tests live under
`mcp-server/application/src/test/java`, beside their owning module.
See the [Java MCP integration test lanes](../../mcp-server/README.md#integration-test-lanes)
for commands, fixture prerequisites and cleanup behavior.

Reusable fixture applications live under [test/fixtures](../fixtures/spring-apps/README.md).
Generated scenario workspaces and sanitized evidence stay under the ignored
`test/.tmp` directory. Fixture sources remain immutable.
