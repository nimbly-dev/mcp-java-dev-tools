# Java MCP Catalog-Describe-Execute

The normative tracked contract amendment is
[`java-mcp-catalog-describe-execute-contract.md`](java-mcp-catalog-describe-execute-contract.md).

## Decision

Java MCP capabilities that have migrated to the operation contract expose one
capability-owned `OperationCatalog`. The catalog is the single resolution point
for a closed action set:

```text
MCP Tool/action
  -> Application request mapper
  -> public Core Feature
  -> capability OperationCatalog
  -> immutable OperationDescriptor
  -> concrete Operation.execute(request)
  -> purpose-owned collaborators
  -> Application response mapper
```

The catalog validates ownership during construction. It rejects duplicate action
registrations, missing closed-enum values, duplicate Tool/action identities, and
descriptors whose executable owner does not match the concrete operation. A
null or unregistered action fails closed with the deterministic
`unsupported operation` message.

The public directory contract remains at
`mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/operation`.
Its implementation is split into purpose-owned packages beneath that boundary:
`binding`, `catalog`, `composition`, `execution`, `manifest`, `manifest/xml`,
`schema`, `safety`, and `trace`. `OperationDescriptor` is immutable metadata
only: it contains Tool/action
identity, typed request/result ownership, concrete executor identity, and
`OperationTraceMetadata`. It contains no executable lambda, I/O, Spring type, or
hidden behavior. `OperationTraceInventory` derives deterministic rows directly
from the validated catalog, including its capability dispatch owner; it does not
perform runtime reflection or classpath scanning.

### MCPJVM-613 package disposition

The relocation is package-only. The resolved member-level guard in
`OperationRelocationApiCompatibilityTest` compares this disposition with the
`c420060` baseline; the Suite argument row is the one approved one-to-three
ownership split.

| Before package | After package | Types |
| --- | --- | --- |
| `...server.core.operation` | `...server.core.operation` | `CatalogPage`, `CatalogQuery`, `OperationDirectory`, `OperationExecutionResult`, `OperationId`, `OperationInvocation`, `OperationInvocationExecutionBridge` |
| `...server.core.operation` | `...server.core.operation.catalog` | `Operation`, `OperationCatalog`, `OperationCatalogEntry`, `OperationCatalogPageBuilder`, `OperationCursor`, `OperationExposure` |
| `...server.core.operation` | `...server.core.operation.binding` | `BoundedOperationRequestDecoder`, `BoundedOperationResultEncoder`, `ContextAwareOperationExecutor`, `DeclaredOperationExecutor`, `MapperOperationResultEncoder`, `OperationExecutor`, `OperationRegistration`, `OperationRegistrationBinding`, `OperationRegistrationContract`, `OperationRequestDecoder`, `OperationRequestDecoderAdapter`, `OperationRequestDecoders`, `OperationRequestEquivalence`, `OperationResultEncoder`, `OperationResultEncoders` |
| `...server.core.operation` | `...server.core.operation.composition` | `CoreOperationDirectory`, `CoreOperationDirectoryOwners` |
| `...server.core.operation` | `...server.core.operation.execution` | `OperationDirectoryException`, `OperationExecutionContext`, `OperationExecutionContextScope`, `OperationExecutionException`, `OperationExecutionStatus`, `OperationFailureException`, `OperationInvocationExecution`, `OperationPayloadValidation` |
| `...server.core.operation` | `...server.core.operation.manifest` | `OperationAlias`, `OperationArgumentDocumentation`, `OperationDescriptor`, `OperationDescriptorMetadata`, `OperationDocumentation`, `OperationManifest`, `OperationManifestAssembler`, `OperationManifestDocument`, `OperationManifestLoader` |
| `...server.core.operation` | `...server.core.operation.manifest.xml` | `OperationManifestXmlReader`, `OperationManifestXmlStructureValidator` |
| `...server.core.operation` | `...server.core.operation.schema` | `CanonicalOperationSchema`, `CoreOperationResultFields`, `CoreOperationResultSchemas`, `OperationJsonTreeLimits`, `OperationSchema`, `OperationSchemaPatternSafety`, `OperationSchemaRules`, `OperationSchemaValidationContext`, `OperationSchemaValueBounds`, `OperationSchemaValueSemantics`, `OperationValidationBudget`, `OperationSchemaValidator` |
| `...server.core.operation` | `...server.core.operation.safety` | `CoreOperationSafetyPolicy`, `OperationCancellationGuarantee`, `OperationCancellationState`, `OperationCancellationSupport`, `OperationJsonByteBuffer`, `OperationJsonSize`, `OperationJsonSnapshot`, `OperationSafetyLimits`, `OperationSafetyPolicy`, `OperationValueRedactor` |
| `...server.core.operation` | `...server.core.operation.trace` | `OperationLegacyIdentity`, `OperationTraceEntry`, `OperationTraceInventory`, `OperationTraceMetadata` |
| `...server.core.operation` | `...server.core.feature.artifactmanagement.model.operation` | `ArtifactOperationArguments` |
| `...server.core.operation` | `...server.core.feature.artifactmanagement.operation` | `ArtifactOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.executionorchestration.model.operation` | `ExecutionOrchestrationArguments` |
| `...server.core.operation` | `...server.core.feature.executionorchestration.operation` | `ExecutionOrchestrationOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.executionprofileexport.model.operation` | `ExecutionProfileExportArguments` |
| `...server.core.operation` | `...server.core.feature.executionprofileexport.operation` | `ExecutionProfileExportOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.failureanalysis.model.operation` | `FailureAnalyzeArguments`, `FailureExpectedFingerprintArguments`, `FailureInvestigationArguments`, `FailureLineHitArguments`, `FailureTerminalArguments`, `FailureVerifyArguments` |
| `...server.core.operation` | `...server.core.feature.failureanalysis.operation` | `FailureAnalysisOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.jvmlifecycle.operation` | `JvmLifecycleOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.probe.model.operation` | `ProbeHttpArguments`, `ProbeOperationArguments` |
| `...server.core.operation` | `...server.core.feature.probe.operation` | `ProbeOperationRegistrations`, `ProbeOperationSchemas` |
| `...server.core.operation` | `...server.core.feature.routesynthesis.operation` | `RouteSynthesisOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.transportexecution.model.operation` | `TransportExecuteArguments`, `TransportExecuteOptionsArguments` |
| `...server.core.operation` | `...server.core.feature.transportexecution.operation` | `TransportExecutionOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.suite.{regression,performance,security}.model.operation` | `SuiteOperationArguments` → `RegressionSuiteOperationArguments`, `PerformanceSuiteOperationArguments`, `SecuritySuiteOperationArguments` |
| `...server.core.operation` | `...server.core.feature.suite.regression.operation` | `RegressionSuiteOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.suite.performance.operation` | `PerformanceSuiteOperationRegistrations` |
| `...server.core.operation` | `...server.core.feature.suite.security.operation` | `SecuritySuiteOperationRegistrations` |

## Kernel fixture scope

Issue #616 completes the capability-neutral kernel only. It does not migrate a
production capability or rewrite an existing operation registration. Synthetic
heterogeneous fixtures provide the bounded binding, execution, normalization,
and cancellation proof; the sequential capability stories own production-row
migration and parity evidence.

## Aggregate operation directory

The Core operation package now also provides the heterogeneous
`OperationDirectory`. `CoreOperationDirectory` is the production composition
root for all fifty-four approved operations: it binds every capability catalog
and feature owner exactly once, then joins those immutable
`OperationRegistration` bindings to the ordered resource index at
`META-INF/mcpjvm/operations/index.xml`. The index loads one shared-XSD
validated capability document per Core operation owner. The XML reader is secure
and bounded, and XML cannot select Java types or executable methods. Java-owned
schemas, binders, normalizers, safety policy, and typed execution remain the
source of truth after the join; there is no reflective schema generator.

The directory exposes deterministic catalog pages, complete manifest-backed
descriptions, and independent exact-ID execution. It validates payload bounds,
approved schema rules, confirmation/deprecation policy, timeout, redaction, and
normalized result shape before returning a bounded execution envelope. Its
generated aggregate manifest and trace inventory retain the released
Tool/action identity (including explicit actionless rows), canonical operation
ID, CDE schema, normalization rule, result comparison rule, and parity
scenario.

The #624 aggregate closure exercise runs one bounded Core scenario for every
operation and checks binding, executor invocation, result normalization, schema,
payload, redaction, and invalid-input behavior. Some aggregate fixtures use
substitute execution collaborators; the generated production owner trace is
joined independently to capability registrations and each substituted row is
identified in the crosswalk. This Core proof does not execute legacy Java or
TypeScript Tools or claim runtime parity. Public facade verification and
cutover remain with #611.

The Core kernel applies these named hard ceilings before argument binding or Core
execution: 1 MiB input, 4 MiB output, depth 64, 100,000 JSON nodes, and 262,144
UTF-8 bytes per string value. Input snapshots use exact decimal parsing after
capped serialization. Strict kernel assembly requires every request decoder and
result encoder to support bounded round-trip streaming; mapper-backed bindings
stream through the hard ceiling before tree materialization, and the streamed
JSON representation is authoritative for normalization. The production
54-operation aggregate uses strict assembly. Generic OperationDirectory
constructors remain migration-compatibility paths for focused legacy fixtures.
In that compatibility path, ordinary encoders are validated for
structure and encoded bytes immediately after their first returned tree and
before any defensive copy or repeated normalization; this compatibility path is
not proof of a future bounded capability migration. Custom request decoders and
ordinary typed mapper decoders use a strict lossless comparison. A typed mapper
decoder may explicitly declare exact Java-owned default values for omitted record
fields. Top-level defaults use field-name keys and nested defaults use escaped RFC
6901 JSON Pointer keys; the complete caller-declared default metadata (path names,
framing, cumulative nodes, and values) is subject to the same bounded JSON checks
before canonical lookup paths or value snapshots are retained. Every rebound value is also checked against the input
schema; no arbitrary added container is accepted.
The per-operation policy may tighten these values but cannot widen them.
Operation timeouts are monotonic and bounded to 100–300,000 ms, defaulting to
30,000 ms; the aggregate executor is capped at 16 concurrent operations and
allows a 1,000 ms cooperative-cancellation grace period.

Typed owners that consume `OperationExecutionContext` receive the monotonic
deadline and cooperative cancellation signal explicitly. Every production
aggregate registration declares context-aware, bounded-delegate, or
explicitly non-cancellable semantics. `LEGACY_UNVERIFIED_CANCELLATION`
remains representable only in migration-compatibility fixtures; strict production
aggregate validation rejects it. Thread
interruption alone is not treated as cancellation proof.

## Structural enforcement

`JavaMcpArchitectureEnforcementTest` runs as part of the Core test phase for the
migrated operation graphs. It enforces these bounded ownership rules on the
shared operation package, the `execution_profile_export` Core
operation/Feature, and the first #606 rollout group (`probe` and
`jvm_lifecycle`) together with their capability-owned operation packages:

Application composition for the rollout group is covered separately by the
configuration, MCP adapter, parity, and packaged STDIO tests; it is not part of
this Core AST structural enforcement test.

The test uses the Java compiler AST for class, method, visibility, and
nesting analysis. It does not use source regular expressions, so comments,
strings, formatting, and equivalent syntax cannot bypass the checks.

- maximum 250 source lines per class file;
- maximum 12 declared methods per class file;
- no nested workflow types;
- no generic `Service`, `Manager`, `Helper`, `Util`, `Common`, or `Shared`
  owners or package segments;
- no public visibility-laundering methods named as helpers, delegates, utility,
  internal, or pass-through code.

The same test accepts cohesive private-method calls and contains compliant and
non-compliant source fixtures for class/method sprawl, nested types, generic
owners, and visibility laundering. Checkstyle and PMD continue to provide the
existing method-level limits; this focused test adds the operation-ownership rules without imposing a
retroactive bulk refactor on capabilities assigned to later migration stories.

## Migration guidance

For a new migrated capability:

1. Keep the public MCP Tool and action values unchanged.
2. Define typed request/result ownership and a purpose-named concrete operation
   owner. A bounded operation table is acceptable when several discriminator
   values share one execution algorithm; do not create one `ActionImpl` class
   per discriminator without distinct behavior.
3. Register every closed action in one capability-owned catalog. Constructing
   the catalog must prove duplicate and missing ownership before use.
4. Build descriptors from explicit metadata at composition time. Keep Core
   Spring-independent and keep Application composition searchable and thin.
5. Keep mapping and external effects in named collaborators owned by the
   capability. Do not introduce generic service, manager, helper, utility,
   common, or shared packages.
6. Add focused behavior/parity evidence and a generated inventory assertion for
   every advertised action.

The aggregate binding is Core-only and does not cut over public Application MCP
registration. Existing capability implementations and registrations continue
to be the released compatibility sources; no production capability row is
rewritten by #616. Later sequential capability stories may migrate those rows
through the same kernel without silently altering the released TypeScript
compatibility implementation.
