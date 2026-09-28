# MCPJVM-637 Live Java CDE Acceptance

Date: 2026-09-28

## Verification

From `mcp-server`, the affected reactor command was:

    & 'C:\Users\Altheo\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd' -B -pl application -am verify

Result: BUILD SUCCESS. Core ran 892 tests, Application ran 50 tests, and packaged STDIO integration ran 5 tests (2 skipped), with no failures or errors. Checkstyle and PMD passed. The Transport Execute Core deadline is now 300000 ms, matching the HTTP provider's maximum request timeout and the XML safety metadata. An earlier run rejected a `description` keyword added to the Core executable JSON Schema; that unsupported change was removed before the successful verification. The validated XML documentation now supplies the HTTP guidance and executable example.

After the final size-limit documentation correction, `mvn -B -pl application -am -Dtest=OperationManifestFragmentTest,CoreOperationDirectoryTest -Dsurefire.failIfNoSpecifiedTests=false verify` passed. It revalidated the changed manifest, rebuilt the packaged JAR, and ran the 5 STDIO integration tests (2 skipped). The final live run below used that JAR.

## Running fixture and MCP client

From the repository root, the Spring Fixtures post-service app ran as a separate JVM:

    java -jar test/fixtures/spring-apps/social-platform/post-service/post-app/target/post-app-0.1.0-SNAPSHOT.jar --server.port=59075

It returned HTTP 200 with `{"status":"UP"}` at `http://127.0.0.1:59075/actuator/health`. The observed fixture PID was 11244. A Node.js MCP SDK client connected over `StdioClientTransport` to the newly packaged Java server:

    java -jar mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar

The client supplied `MCP_WORKSPACE_ROOT` as the repository root. The fixture used in-memory state. No Sidecar attachment or persisted Artifact was needed for this HTTP transport path.

## Observed CDE calls

- `tools/list` returned exactly `operation_catalog`, `operation_describe`, and `operation_execute`.
- `operation_catalog({api:"transport_execute"})` returned only `transport_execute.execute`. `operation_describe({operationId:"transport_execute.execute"})` returned the Java-owned input schema, HTTP request instructions and concrete GET example, and safety policy: `confirmationRequired=true`, `sideEffect=transport_request`, `credentialPolicy=caller_may_supply_credentials`, `redactionPolicy=redact_sensitive_fields`, `timeoutMillis=300000`. Neither public response contained alias or deprecation fields.
- `operation_execute({operationId:"transport_execute.execute",arguments:{protocol:"http",request:{method:"GET",url:"http://127.0.0.1:59075/actuator/health"}},confirmed:true})` returned `succeeded`, transport `pass`, HTTP 200, and body preview `{"status":"UP"}`. A local relay then delayed forwarding a GET to that same fixture health endpoint for 31 seconds. With `request.timeoutMs=45000`, the CDE call returned `succeeded`, transport `pass`, HTTP 200, and the fixture's health body after 31047 ms, proving execution can pass the former 30000 ms deadline.
- The same operation with an HTTP POST to `/api/v1/posts`, an `Authorization: Bearer alice-token` header, and body `{content:"mcpjvm-637-live-created",visibility:"PUBLIC",tags:["mcpjvm637"]}` returned `confirmation_required` with `confirmed:false`; a direct fixture GET for the tag still showed zero matching posts. With `confirmed:true`, the CDE call returned `succeeded`, transport `pass`, HTTP 201, and the created post ID 104. A direct fixture GET returned HTTP 200 with one matching post containing the created content.
- A confirmed GET targeting `http://example.com/secret?api_key=<sentinel>` returned transport `blocked_invalid`, reason `http_host_not_allowed`. Bounded metadata retained `http://example.com/secret?api_key=[REDACTED]`; the sentinel was absent from the MCP result.
- A confirmed POST with a JSON array body containing five 230000-character strings (each below the per-string cap, but with encoded `arguments` above 1 MiB) returned `invalid_input`, reason `operation_input_invalid`, before the HTTP provider ran. Public Describe now states both CDE ceilings: 1048576 bytes for encoded `arguments` and 262144 UTF-8 bytes per string. The provider's separate 4 MiB body ceiling remains available to direct Core/suite callers; public CDE cannot currently deliver a 4 MiB body.

## Ownership and cleanup

The CDE registration already invoked the substantive `ExecuteTransportAction` through the shared `TransportExecutionFeature` boundary. No Transport Execute-specific Java MCP adapter remained to delete. The action's Spring Bean and Core interface remain because the CDE directory and regression, performance, and security suite owners consume that same substantive action. The shared manifest alias and provenance machinery remains for other capabilities until #611 final cleanup; it does not appear in public CDE discovery. The unreferenced Transport Execute TypeScript parity fixture was removed. Node STDIO source removal belongs to #611.

The client closed and the fixture was stopped. An OS process query found PID 11244 absent and no listener on port 59075. No fixture state or project Artifact was persisted. Raw MCP transcripts and full response dumps are not included in this change. Supporting a 4 MiB body through public CDE would require a separately scoped change to shared Core invocation and JSON-string hard ceilings; this remains an explicit gap.
