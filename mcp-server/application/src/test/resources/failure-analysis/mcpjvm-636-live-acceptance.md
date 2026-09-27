# MCPJVM-636 Live Java CDE Acceptance

Date: 2026-09-26

## Affected Maven verification

From mcp-server, the affected reactor was verified with:

    & '<user-profile>\.m2\wrapper\dists\apache-maven-3.9.11\<distribution>\bin\mvn.cmd' -B -pl application -am verify

Result: BUILD SUCCESS. Core ran 890 tests, Application ran 64 tests, and STDIO integration ran 5 tests with 2 skipped. There were no failures or errors. Checkstyle and PMD passed.

## Fixture and packaged MCP server

From the repository root, the Spring Fixtures post-service app was launched with JFR stack depth set to 256:

    java -XX:FlightRecorderOptions=stackdepth=256 -jar test/fixtures/spring-apps/social-platform/post-service/post-app/target/post-app-0.1.0-SNAPSHOT.jar

Its health endpoint returned HTTP 200 at http://127.0.0.1:18083/actuator/health. The captured fixture process was PID 22680 with process-start fence 1790435227324.

The packaged MCP server was launched over STDIO using the Node.js MCP SDK StdioClientTransport:

    java -jar mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar

The launch environment supplied MCP_WORKSPACE_ROOT, MCP_JAVA_ATTACH_HELPER_JAR, and MCP_JAVA_AGENT_JAR; machine-specific workspace paths are omitted from this record.

The live run used 15 MCP calls and four fixture HTTP exchanges. The outcomes and relevant runtime state are summarized below; raw MCP transcripts and full request/response dumps are intentionally excluded from the changelist.

## CDE surface and runtime workflow

tools/list returned exactly operation_catalog, operation_describe, and operation_execute. The Failure Analysis catalog exposed failure_analysis.analyze_trace and failure_analysis.verify_reproduction; their Java-owned schemas and safety metadata were returned by operation_describe.

The fixture accepted the setup POSTs for an in-memory tag and its lock (both HTTP 200). The DELETE trigger returned HTTP 500 with the controlled downstream 405 message before deletion. A strict Probe for com.example.social.post.app.service.TagLifecycleService#deleteTag:26 recorded one hit and a capture ID.

JFR recorded the fixture JVM's actual jdk.JavaExceptionThrow event for java.lang.IllegalStateException with the controlled downstream 405 message. The complete event contained 145 frames and was not truncated. The live analyze_trace MCP request sent the complete captured stack formatted as a Java trace (16,489 characters, SHA-256 4475d86cb195ca3a76d0ba600c23fcffb5e8ff6612f32f3e02390cdffda91aea); no frames were omitted. Its TagLifecycleService.deleteTag frame matched the strict Probe key at line 26. The response was ANALYZED, reasonCode=ok, diagnosisClaimed=false, with a complete fingerprint for java.lang.IllegalStateException and no diagnosis claim.

The positive verify_reproduction call matched the fingerprint from that analyzed trace and the Sidecar capture: REPRODUCED, reasonCode=ok, sidecarOutcome=matched, and hitCount=1. A method mismatch returned NOT_REPRODUCED with different_application_frame and no diagnosis. An invalid trace returned INCONCLUSIVE with failure_fingerprint_incomplete and no diagnosis.

## Dispatch ownership

Core owner composition passes the concrete FailureAnalysisActionHandler list to CDE registration. Registration validates exactly one handler per action and binds each operation executor directly to its substantive handler. The dispatch-only FailureAnalysisFeature and DefaultFailureAnalysisFeature facades are removed. The obsolete Failure Analysis-specific MCP adapter, wrappers, schemas, and parity resources were also removed.

## Cleanup

The CDE jvm_lifecycle.deactivate call returned OK/deactivated for PID 22680 with the captured process-start fence. The fixture process then stopped; an OS process query confirmed PID 22680 was absent and port 18083 was closed. The Sidecar and fixture state were in-memory only, and no project Artifact was created or changed.

