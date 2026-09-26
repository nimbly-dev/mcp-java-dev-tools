# MCPJVM-635 Route Synthesis acceptance

Recorded 2026-09-26 against the rebuilt Java STDIO server and the
`social-platform/post-service` fixture. The commands below normalize the local
repository root as `<repo>`; the fixture used port `53490` in this run. The
server ran on Corretto 21.

```text
$JAVA -jar mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar --workspace-root=<repo>
$JAVA -jar test/fixtures/spring-apps/social-platform/post-service/post-app/target/post-app-0.1.0-SNAPSHOT.jar --server.port=53490
```

## Public CDE contract

- `tools/list` returned exactly `operation_catalog`, `operation_describe`, and
  `operation_execute`.
- `operation_catalog(query="route_synthesis")` returned exactly
  `route_synthesis.class_methods`, `route_synthesis.create_recipe`,
  `route_synthesis.discover_handlers`, and `route_synthesis.infer_target`.
- Catalog and Describe output contained no legacy alias, deprecation, or
  replacement fields. The XML alias elements used to join operation identity
  were preserved.
- Describe for `route_synthesis.create_recipe` reported
  `filesystem_write`, `confirmationRequired=true`,
  `credentialPolicy=caller_may_supply_credentials`, and
  `redactionPolicy=redact_sensitive_fields`.

## Live fixture exchange

The fixture accepted this request:

```http
POST http://127.0.0.1:53490/api/v2/tenant/demo/tags
Content-Type: application/json

{"name":"fixture-tag"}
```

It returned HTTP `200` with:

```json
{"tenantId":"demo","operation":"create","status":"ok","request":{"name":"fixture-tag"}}
```

The CDE workflow discovered three `TagLifecycleController` handlers, each with
a runtime-validated executable line. The selected handler and inferred target
were `POST /api/v2/tenant/{tenantId}/tags`; the confirmed recipe returned the
same method and path with `resultType=recipe` and `status=ready`.

## Safety and bounded failure checks

| Execute case | Recorded result |
| --- | --- |
| `create_recipe`, `confirmed=false` | `confirmation_required`; reason `operation_confirmation_required` |
| `create_recipe`, `confirmed=true`, bearer credential supplied | Succeeded; recipe ready; supplied sentinel absent from the result |
| `create_recipe`, `confirmed=true`, basic credentials supplied | Succeeded; recipe ready; supplied sentinels absent from the result |
| `class_methods` for an unknown class | Succeeded as a bounded result with `class_not_found` and `target=null` |

The fixture Sidecar attach and deactivate calls succeeded. MCP STDIO stdout
contained only JSON-RPC messages and the server exited with code `0`. The
harness then terminated the fixture after deactivation; its Windows process
exit code was `1`.

## Verification

- `mvn -pl core -am verify -Dtest=RouteFailureOperationRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false` — 41 tests passed; Checkstyle reported zero violations.
- `mvn -pl application -am package -DskipTests` — succeeded and rebuilt the packaged server used above.
- The earlier affected application verification passed 888 Core tests and 71 Application tests; this discovery-only follow-up was then covered by the focused Core verification above.
- The focused discovery test covers an array-valued `@RequestMapping(path={...}, method={...})` and confirms the constructor and unannotated helper are not returned as handlers.

`create_recipe` returns a ready request recipe; it does not persist an Artifact.
This acceptance proves recipe synthesis and live route agreement, not Artifact
creation.
