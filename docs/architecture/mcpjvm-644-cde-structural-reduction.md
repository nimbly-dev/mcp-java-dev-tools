# MCPJVM-644 CDE structural reduction

This document records the baseline, capability-by-capability disposition, and
final verification evidence for #644. Counts are diagnostic evidence, not
deletion targets.

## Baseline

Baseline commit: `74553a50`.

| Area | Baseline |
| --- | ---: |
| Production Java files (`mcp-server/core` + `mcp-server/application`) | 769 |
| Production Java interface declarations | 66 |
| Interfaces with exactly one direct named implementation | 31 |
| Java test files | 95 |
| Capability-local executable `*OperationCatalog` files | 3 |
| Aggregate executable operation directories | 1 |
| Operation XML/XSD files | 13 |

The accepted public MCP Tool surface is
`operation_catalog`, `operation_describe`, and `operation_execute`.
Spring-independent Core boundaries, the Sidecar protocol, Artifact formats,
and substantive capability collaborators are retained. The pre-CDE status
Resource and `debug_check` Tool are removed.

## Probe disposition

Baseline CDE path:

```text
OperationRegistration
  -> ProbeOperation
  -> ProbeActionHandler
  -> Probe action implementation
  -> ProbeTargetResolver / ProbeEndpointClient / output store
```

`ProbeOperationCatalog` constructs `ProbeOperation` wrappers and performs a
second action selection after the aggregate `OperationDirectory` has already
resolved the canonical operation ID.

| Production type | Disposition | Reason / surviving consumer |
| --- | --- | --- |
| `ProbeOperationCatalog` | DELETE | Capability-local executable catalog duplicates aggregate CDE resolution. |
| `ProbeOperation` | DELETE | Forwarding operation wrapper delegates to one `ProbeActionHandler`. |
| `ProbeOperationRegistrations` | KEEP/MERGE | Remains the canonical seven-row registration owner; binds each registration directly to its concrete handler. |
| `ProbeActionHandler` | KEEP | Typed owner contract used for Spring collection composition and the non-CDE `ProbeFeature` boundary. |
| `DefaultProbeFeature` / `ProbeFeature` | KEEP | Named non-CDE consumers are Suite composition and Route Synthesis runtime evidence. Its dispatch must not be used by CDE. |
| Seven `Probe*Action` implementations | KEEP | Substantive validation, failure mapping, cancellation/interruption, Sidecar HTTP behavior, and profiler persistence owners. |
| `ProbeTargetResolver` / `ProbeEndpointClient` | KEEP | Registry/routing and external Sidecar protocol boundaries. |
| `ProbeProfilerOutputStore` | KEEP | Filesystem persistence boundary for profiler output. |

Preserved behavior: typed request creation, schema validation, confirmation and
side-effect policy, bounded cancellation, interrupted-wait mapping, registry
resolution, Sidecar requests, response compaction/redaction, and profiler
output persistence.

Implementation-coupled catalog/operation tests will be removed or rewritten as
registration/Feature behavioral tests. Existing action tests, live Probe
acceptance, and packaged STDIO evidence remain the behavioral proof.

## JVM Lifecycle disposition

Baseline CDE path:

```text
OperationRegistration
  -> JvmLifecycleOperation
  -> JvmLifecycleActionHandler
  -> ListJvmsAction / AttachAction / DeactivateAction
  -> JVM helper / artifact resolver / execution policy
```

`JvmLifecycleOperationCatalog` constructs one forwarding operation wrapper per
action and performs another action lookup after the aggregate directory has
already selected the canonical operation.

| Production type | Disposition | Reason / surviving consumer |
| --- | --- | --- |
| `JvmLifecycleOperationCatalog` | DELETE | Capability-local executable catalog duplicates aggregate CDE resolution. |
| `JvmLifecycleOperation` | DELETE | Forwarding wrapper adds metadata but delegates execution to one action handler. |
| `JvmLifecycleOperationRegistrations` | KEEP/MERGE | Remains the canonical three-row registration owner and binds rows directly to concrete handlers. |
| `JvmLifecycleActionHandler` | KEEP | Typed concrete-owner contract used by Spring collection composition and the non-CDE Feature boundary. |
| `DefaultJvmLifecycleFeature` / `JvmLifecycleFeature` | KEEP | Execution Orchestration and application runtime shutdown are named non-CDE consumers. CDE does not dispatch through it. |
| `ListJvmsAction` / `AttachAction` / `DeactivateAction` | KEEP | Substantive process discovery, identity validation, Sidecar lifecycle, and outcome-mapping owners. |
| JVM helper, artifact resolver, host and execution policies | KEEP | External process, artifact integrity, allowed-host, and command-policy boundaries. |

Preserved behavior: closed three-action coverage, typed request creation,
confirmation normalization, schema and side-effect policy, delegated deadline
semantics, PID/start-time identity checks, helper execution, Sidecar artifact
resolution, and lifecycle result mapping.

## Artifact Management disposition

Baseline CDE path:

```text
OperationRegistration
  -> ArtifactOperation
  -> ProbeConfigOperations / ProjectContextOperations / PlanOperations /
     RunResultOperations / ExecutionExportOperations
  -> ArtifactManagementSupport
  -> JSON / SQLite / filesystem persistence
```

`ArtifactOperationCatalog` owns a second executable registry, while
`ArtifactOperation` is a forwarding function-and-metadata wrapper. The five
Artifact family owners already contain the substantive behavior.

| Production type | Disposition | Reason / surviving consumer |
| --- | --- | --- |
| `ArtifactOperationCatalog` | DELETE | Second executable catalog duplicates aggregate CDE lookup. Its non-CDE callers can use the intentional Feature boundary. |
| `ArtifactOperation` | DELETE | Per-operation forwarding wrapper; registration metadata and method reference merge into `ArtifactOperationRegistrations`. |
| `ArtifactOperationRegistrations` | KEEP/MERGE | Canonical 31-row CDE binding; directly invokes a method on one of the five family owners. |
| Five `*Operations` family owners | KEEP | Substantive validation, persistence, migration, query, and export owners. |
| `DefaultArtifactManagementFeature` / `ArtifactManagementFeature` | KEEP | Execution Orchestration and trusted direct Suite execution are named non-CDE consumers. Its family/action selection remains domain behavior and is absent from the CDE path. |
| `ArtifactManagementSupport`, stores, workspace provider | KEEP | Filesystem, JSON, SQLite, workspace trust, locking, and persistence boundaries. |

Preserved behavior: family/action allowlists, request decoding, confirmation
and safety policy, deterministic continuation, workspace containment, JSON and
SQLite persistence, migration/rebuild/backfill/cutover behavior, execution
export generation, and existing result envelopes.

## Remaining capability dispositions

| Capability | Direct CDE owner | Intermediate disposition and justification |
| --- | --- | --- |
| Route Synthesis | concrete `RouteSynthesisActionHandler` | KEEP registrations and action handlers. The four canonical rows bind directly to their selected algorithm owner and perform no second operation lookup. |
| Failure Analysis | concrete `FailureAnalysisActionHandler` | KEEP registrations and action handlers. Rows already bind directly to the selected action owner; session/provider collaborators remain substantive diagnostic boundaries. |
| Transport Execute | `TransportExecutionFeature` | KEEP. One CDE operation invokes the transport execution boundary, which owns HTTP/SQL provider selection and safety rather than MCP operation selection. |
| Execution Profile Export | `ExecutionExportArtifactGateway` | KEEP. One CDE operation invokes the Artifact export/persistence boundary directly. |
| Execution Orchestration | concrete `ExecutionOrchestrationActionHandler` | KEEP registrations and handler. The single row already binds directly to its substantive workflow owner. |
| Regression Suite | `RegressionSuiteFeature` through `TrustedSuiteExecution` | KEEP. `execute_plan` and `preflight` are distinct registrations; trusted workspace/plan resolution and Suite execution are substantive cross-feature boundaries. |
| Performance Suite | `PerformanceSuiteFeature` through `TrustedSuiteExecution` | KEEP. Single resolved operation; trusted plan loading and persistence remain substantive. |
| Security Suite | `SecuritySuiteFeature` through `TrustedSuiteExecution` | KEEP. Single resolved operation; credential binding, trusted plan loading, and persistence remain substantive security boundaries. |

No remaining capability type is `TEMPORARY`. None of the retained Feature
boundaries is used to rediscover an operation on a multi-operation CDE path.

## Final reduction and cutover evidence

| Area | Baseline | Final | Change |
| --- | ---: | ---: | ---: |
| Production Java files | 769 | 767 | -2 |
| Production Java interface declarations | 66 | 64 | -2 |
| Interfaces with exactly one direct named implementation | 31 | 32 | +1 |
| Java test files | 95 | 97 | +2 |
| Capability-local executable `*OperationCatalog` files | 3 | 0 | -3 |
| Maximum runtime dispatch stages from MCP adapter to substantive owner | 3 | 2 | -1 |
| Redundant catalog/forwarding dispatch Java types | 7 | 0 | -7 |
| Application migration adapter/boundary Java files | 12 | 0 | -12 |
| Legacy Tool/action alias mappings | 50 | 0 | -50 |
| Migration-only Java tests | 12 | 0 | -12 |
| Public Spring AI MCP Tools | mixed legacy + CDE code | 3 | exactly CDE |
| Public Spring AI MCP Resources | 1 | 0 | -1 |
| MCP schema post-processors | 1 | 0 | -1 |
| Fixed public facade output schemas | 0 | 3 | +3 |
| XML alias rows | 50 | 0 | -50 |
| XML argument `type`/`required` policy values | 636 | 0 | -636 |
| XML argument default values | 22 | 0 | -22 |
| XML safety policy values | 432 across 54 rows | 0 | -432 |

The final CDE path is:

```text
Spring AI STDIO
  -> OperationMcpTools
  -> one aggregate OperationDirectory
  -> OperationRegistration
  -> substantive capability owner
  -> real external/persistence collaborator
```

Removed migration-only structures include the generic secondary
`OperationCatalog`, capability-local Probe/JVM/Artifact catalogs and forwarding
wrappers, alias/provenance/compatibility metadata, pre-CDE application action
adapters and boundary wrappers, `debug_check`, the status Resource, and
cross-runtime parity fixtures. Operation XML now contains descriptive text only;
schemas and safety policy are assembled from Java registrations.

The former `OperationMcpOutputSchemaPostProcessor` is deleted. The three public
facade output schemas are now explicit, fixed JSON Schema resources registered
with the three annotated CDE methods; no Spring lifecycle hook mutates Tool
definitions after registration. Catalog page-size configuration binds from
`mcp.operation-catalog`, and Application maps it into the Core-owned
`CatalogQueryPolicy`, which enforces both the configured deployment maximum and
the hard Core maximum.

The additional structural counts use these explicit rules:

- A runtime dispatch stage selects or forwards execution after
  `OperationMcpTools` and before the substantive capability owner. The affected
  Probe, JVM Lifecycle, and Artifact paths previously traversed the aggregate
  directory/registration plus a forwarding `*Operation`; they now traverse
  only the aggregate directory/registration before invoking the owner.
- Redundant dispatch types are the generic `OperationCatalog`, the three
  capability-local `*OperationCatalog` classes, and their three forwarding
  `*Operation` classes. All seven are deleted.
- Migration adapters/boundaries are the six Java files under
  `application/.../mcp/tools/action` and six under `application/.../mcp/error`.
  These existed to translate or normalize the retired per-feature MCP surface;
  all twelve are deleted.
- Alias mappings are concrete `<alias>` rows in the committed operation XML,
  not container elements or documentation mentions. The baseline contains 50;
  the final manifests and Java model contain none.
- Migration-only tests are the twelve deleted relocation-snapshot,
  catalog-wrapper, boundary-mapper, legacy Tool/Resource, and Java/TypeScript
  parity test classes. The #613 resolved-API fixture was already removed by
  #616, so #644 retires both obsolete #610-to-#613 relocation guards instead of
  restoring migration data solely to keep a historical test runnable. The new
  `DefaultJvmLifecycleFeatureTest` is behavioral coverage for a surviving
  non-CDE Feature boundary, so it is not counted as a migration test.
- One added Java test-support file is `McpServerAcceptanceConstants`, which
  centralizes immutable packaged-acceptance protocol, fixture, Strict Line, and
  plan text after splitting the former monolithic integration test. It contains
  no executable test and adds no production or migration layer.
- The other added Java test file is `RegressionExternalVerificationTest`, which
  reproduces nullable SQL parameters and quoted/commented colons through real
  SQLite execution. It is surviving product-behavior coverage, not a migration
  guard or dispatch abstraction.
- The one-interface/one-implementation count includes top-level production
  interfaces with exactly one direct named production `class`, `record`, or
  `enum` implementation in Core or Application. It excludes anonymous/lambda
  implementations and indirect subclassing. The same declaration/implementation
  rule yields 31 at baseline and 32 in the final tree. The added
  `RegressionCorrelationRuntime`/`SidecarRegressionCorrelationRuntime` pair is
  retained as the real Sidecar protocol boundary for leases, cursors, bounded
  event reads, and release; it is not an operation router or migration adapter.
- The XML policy baseline is counted from the 54 action rows at `74553a50`:
  50 alias mappings; 318 `type` plus 318 `required` argument values; 22 default
  values; and eight safety values on each of 54 safety rows (432 values).
  Descriptive identity, summaries, descriptions, tags, argument names/descriptions,
  and examples are deliberately excluded. Every counted executable value is zero
  in the final XML because Java registrations own those policies.

## Retained CDE dispatcher and handler inventory

There is one transport adapter (`OperationMcpTools`), one executable lookup
type (`OperationDirectory`), and one aggregate composer
(`CoreOperationDirectory`). The composer assembles the following 11 registration
owners and 54 canonical rows; none of these owners performs another operation-ID
lookup after its row has been selected.

| Registration owner | Rows | Selected substantive owner |
| --- | ---: | --- |
| `ArtifactOperationRegistrations` | 31 | One method on the selected Probe Config, Project Context, Plan, Run Result, or Execution Export owner |
| `ProbeOperationRegistrations` | 7 | One of seven concrete `ProbeActionHandler` implementations |
| `JvmLifecycleOperationRegistrations` | 3 | One of three concrete `JvmLifecycleActionHandler` implementations |
| `RouteSynthesisOperationRegistrations` | 4 | One of four concrete `RouteSynthesisActionHandler` implementations |
| `FailureAnalysisOperationRegistrations` | 2 | One of two concrete `FailureAnalysisActionHandler` implementations |
| `TransportExecutionOperationRegistrations` | 1 | `TransportExecutionFeature` |
| `ExecutionProfileExportOperationRegistrations` | 1 | `ExecutionExportArtifactGateway` |
| `ExecutionOrchestrationOperationRegistrations` | 1 | `ExecutionOrchestrationActionHandler` |
| `RegressionSuiteOperationRegistrations` | 2 | `RegressionSuiteFeature` through `TrustedSuiteExecution` |
| `PerformanceSuiteOperationRegistrations` | 1 | `PerformanceSuiteFeature` through `TrustedSuiteExecution` |
| `SecuritySuiteOperationRegistrations` | 1 | `SecuritySuiteFeature` through `TrustedSuiteExecution` |

The retained handler interfaces express existing collection composition or
cross-feature/external boundaries. CDE does not call the multi-action Probe,
JVM Lifecycle, or Artifact Feature facades to redispatch these rows.

One transport wrapper is added:
`CancellationAwareStdioServerTransportProvider`. Spring AI's bundled MCP Java
SDK STDIO provider does not expose a server-side handler extension for
`notifications/cancelled`. The wrapper is therefore retained at the protocol
boundary, delegates session lifecycle, notifications, client requests, and
protocol versions to the SDK provider, and adds only request-ID cancellation.
It is not an operation router or migration adapter. Packaged acceptance proves
that the active HTTP exchange remains live before cancellation, closes within
five seconds afterward, suppresses the cancelled JSON-RPC response, and leaves
the same MCP session usable.

Counts use the committed baseline tree and the proposed final changelist by the
same rule. The final inventory includes non-ignored untracked Java sources and
excludes deleted tracked paths. This avoids the earlier invalid comparison of a
working-tree baseline with a tracked-only final count. No production Java source
is ignored; the 13 Probe and Route Synthesis `model/target` sources are included
in the proposed changelist.

The npm package is private and has no `main`, `bin`, or `start` route. The root
and transport-package Node MCP server entrypoints, feature-specific handlers,
SDK dependency, and server integration tests are deleted. No package metadata,
startup command, or generated client configuration references `dist/server.js`.
Install and update scripts package the Java server and generate `java -jar`
client configuration. Legacy TypeScript sources are read-only migration
references: behavior identified there must be implemented in Java, and no
TypeScript execution counts as #644 acceptance.

Verification through 2026-10-10 on Windows with Java 21.0.12.1:

- `mvn -B clean verify`: success.
- Core: 888 tests, zero failures/errors/skips.
- Application unit tests: 25 tests, zero failures/errors/skips.
- Packaged raw-STDIO integration: 10 tests, zero failures/errors, 2 expected
  Windows POSIX-signal skips.
- Final acceptance constants cleanup on 2026-10-11: the two affected packaged
  tests (`McpServerCorrelationIT` and `McpServerJvmCapabilitiesIT`) passed again,
  with zero failures/errors/skips; Checkstyle, PMD, and Enforcer also passed.
- Installer/SQL repairs on 2026-10-11: 41 focused Core tests and the real
  `user-app` packaged workflow passed; Checkstyle, PMD, and Enforcer passed.
  Git Bash path serialization tests covered native/shell/relative paths with
  spaces across all client formats. A separate native Windows Java process
  initialized MCP using the generated configuration and exited 0. These scoped
  checks supplement, rather than replace, the historical clean-reactor count.
- Packaged catalog-policy acceptance starts the executable with a configured
  default of 2 and maximum of 3, proves the omitted limit returns two rows, and
  proves limit 4 fails in Core with `catalog_limit_invalid`.
- `tools/list` proves the three fixed facade schemas expose nullable fields and
  arbitrary JSON values without any schema post-processor.
- Live lifecycle acceptance discovered a real fixture JVM through
  `jvm_lifecycle.list_jvms`, attached the packaged Sidecar Agent, exercised
  Probe, Route Synthesis, Failure Analysis, and a JMeter-backed Performance
  Suite with Strict Line evidence, deactivated the Sidecar, and confirmed
  target health; acceptance gap was `none`.
- The initialize response advertised Tools only, `tools/list` returned exactly
  the three CDE tools, stdout contained only JSON-RPC, and the server exited 0.
- Packaged cancellation consumes `notifications/cancelled`, interrupts the
  in-flight request, observes the target-side HTTP disconnect within five
  seconds, suppresses its response, and leaves the session responsive.
- Packaged timeout returns `transport_request_timeout` with `blocked_runtime`.
- Packaged feature acceptance exercised Artifact Management, HTTP transport,
  execution orchestration, execution-profile export, Regression, Security,
  Probe, Route Synthesis, Failure Analysis, Performance, and JVM lifecycle
  against real fixture applications launched from their Maven-built JARs. The
  Regression proof also attaches Sidecars to the producer and consumer JVMs,
  correlates a response-derived event key to consumer runtime evidence, persists
  `correlation.json`, and proves a wrong key fails closed. A two-plan profile
  additionally persists and resumes `crossPlan:true` state, propagates the
  response-derived key into the later consumer plan, and emits the deterministic
  suite-level correlation summary.
  Regression verification additionally exercised an HTTP Watcher, external HTTP
  verification, env-backed canonical SQLite verification with bound parameters,
  and unpinned source-to-Strict-Line resolution against the live `user-app`
  Sidecar; the test supplied the class and method selectors but not line 20.
  A separate Docker run repeated the Artifact/transport/Regression workflow.
  Exact invocations and per-scenario outcomes are recorded in
  `docs/acceptance/mcpjvm-644-cde-cutover.md`.
- The application module packages `social-platform/user-service/user-app`,
  `event-service/event-producer-app`, and `event-service/event-consumer-app`
  plus their reactor dependencies during `pre-integration-test`. Missing
  fixture output fails the build; the packaged acceptance tests no longer use
  JUnit assumptions or silently skip.

This technical evidence does not constitute the explicit breaking-cutover
approval required by #611. That public approval remains an external release
gate.
