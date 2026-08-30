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

The shared contract lives in
`mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/operation`.
`OperationDescriptor` is immutable metadata only: it contains Tool/action
identity, typed request/result ownership, concrete executor identity, and
`OperationTraceMetadata`. It contains no executable lambda, I/O, Spring type, or
hidden behavior. `OperationTraceInventory` derives deterministic rows directly
from the validated catalog, including its capability dispatch owner; it does not
perform runtime reflection or classpath scanning.

## Reference capability

`execution_profile_export` is the bounded reference migration outside the
Artifact Management capability for this phase. Its `export` action is owned by
`ExportExecutionProfileOperation`, registered by
`ExecutionProfileExportOperationCatalog`, and composed explicitly by
`CoreOperationDirectory`. The operation still delegates Artifact
generation through the existing `ExecutionExportArtifactGateway`; no Artifact,
Sidecar, MCP schema, status, reason-code, or output behavior was changed.

The trace metadata names the MCP adapter, request mapper, Core Feature, response
mapper, focused TypeScript-parity evidence, side-effect classification, and
collaborator roles. The generated inventory additionally names the concrete
capability catalog that dispatches the action. The focused parity fixture
remains the proof that the migrated route preserves the released TypeScript
contract.

## Aggregate operation directory

The Core operation package now also provides the heterogeneous
`OperationDirectory`. `CoreOperationDirectory` is the production composition
root for all fifty-four approved operations: it binds every capability catalog
and feature owner exactly once, then joins those immutable
`OperationRegistration` bindings to the versioned XML document at
`META-INF/mcpjvm/operations/manifest.xml`. The XML reader is secure and
bounded, and XML cannot select Java types or executable methods. Java-owned
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

## Structural enforcement

`JavaMcpArchitectureEnforcementTest` runs as part of the Core test phase for the
migrated operation graphs. It enforces these bounded ownership rules on the
shared operation package, the `execution_profile_export` Core
operation/Feature, and the first #606 rollout group (`probe` and
`jvm_lifecycle`) together with their capability-owned operation packages:

Application composition for the rollout group is covered separately by the
configuration, MCP adapter, parity, and packaged STDIO tests; it is not part of
this Core AST structural enforcement test.

The test uses the Java compiler AST for class, method, visibility, nesting, and
private-call analysis. It does not use source regular expressions, so comments,
strings, formatting, and equivalent syntax cannot bypass the checks.

- maximum 250 source lines per class file;
- maximum 12 declared methods per class file;
- at most one private behavior method per class file;
- no private-helper call chains;
- no nested workflow types;
- no generic `Service`, `Manager`, `Helper`, `Util`, `Common`, or `Shared`
  owners or package segments;
- no public visibility-laundering methods named as helpers, delegates, utility,
  internal, or pass-through code.

The same test contains compliant and non-compliant source fixtures for private
width/depth, class/method sprawl, nested types, generic owners, and visibility
laundering. Checkstyle and PMD continue to provide the existing method-level
limits; this focused test adds the operation-ownership rules without imposing a
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
registration. Route Synthesis, Failure Analysis, Transport Execution, the suite
Features, and Execution Orchestration continue using their existing
`EnumActionDispatcher`-backed capability implementations behind the explicit
typed directory adapters. The migrated Java paths do not silently alter the
released TypeScript compatibility implementation.
