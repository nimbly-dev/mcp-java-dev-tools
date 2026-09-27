# MCPJVM-637 Transport Execute live CDE acceptance

Date: 2026-09-27. Scope: `transport_execute.execute` through the packaged Java STDIO server.

## Build and launch

From `mcp-server`, the affected reactor command was:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -B -pl application -am verify
```

Result: BUILD SUCCESS. Core: 890 tests, zero failures/errors/skips. Application: 59 tests, zero failures/errors/skips. Packaged-JAR STDIO integration: 5 tests, zero failures/errors, 2 skipped. Checkstyle and PMD passed.

The Spring Fixtures user app and MCP server were launched from the repository root with these Java commands. The MCP client supplied `MCP_WORKSPACE_ROOT` set to the repository root and connected using the Node MCP SDK `StdioClientTransport`.

```powershell
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar test/fixtures/spring-apps/social-platform/user-service/user-app/target/user-app-0.1.0-SNAPSHOT.jar --server.port=18237
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar
```

The fixture health endpoint returned HTTP 200 at `http://127.0.0.1:18237/actuator/health`. The client closed the MCP connection and stopped the fixture after the run.

## Observed MCP behavior

- `tools/list` returned exactly `operation_catalog`, `operation_describe`, and `operation_execute`.
- `operation_catalog({"query":"transport_execute","limit":10})` returned `transport_execute.execute`. `operation_describe({"operationId":"transport_execute.execute"})` returned Java-owned required arguments `protocol` and `request`, the `http`/`grpc`/`kafka`/`custom` protocol enum, and safety metadata: confirmation required, caller may supply credentials, and sensitive fields are redacted. Neither discovery result exposed alias or deprecation fields.
- `operation_execute` with `operationId="transport_execute.execute"`, HTTP `GET http://127.0.0.1:18237/api/v1/users/bob`, and `confirmed=true` returned HTTP 200. The response body preview identified Bob and reported `followersCount=0`.
- The authenticated HTTP `POST /api/v1/users/bob/follow` with `confirmed=false` returned `confirmation_required` / `operation_confirmation_required` without executing. The same request with `confirmed=true` returned HTTP 204. A subsequent confirmed GET returned HTTP 200 and `followersCount=1`, proving the fixture's in-memory effect.
- A confirmed GET to an unallowlisted host with a sensitive query value and Authorization header returned the transport-owned `blocked_invalid` / `http_host_not_allowed` outcome. A confirmed `TRACE` to the fixture returned `blocked_invalid` / `http_method_not_allowed`. The tested sensitive value was absent from the blocked output, and the fixture Authorization value was absent from successful outputs. In these two cases the CDE envelope reported `succeeded` because the registered operation completed; the nested transport result carried the rejection.

The Sidecar was not needed for HTTP execution. This scenario exercised fixture memory, not Artifact persistence. It does not claim live coverage for non-HTTP providers or every transport failure path.

## Code disposition

The CDE registration now invokes the substantive `ExecuteTransportAction` through the shared `TransportExecutionFeature` boundary. That boundary remains because the curated Suites call transport execution. The Transport Execute-specific Java MCP adapter, transport schema post-processor, request/response wrappers, one-action dispatch facade, handler interface, discriminator, and their implementation-coupled tests were removed. The HTTP provider, host and method policy, redactor, provider registry, and Suite consumers remain. Node STDIO removal remains assigned to #611 final cleanup.
