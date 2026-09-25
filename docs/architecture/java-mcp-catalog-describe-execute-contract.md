# Tracked Java MCP Contract Amendment

Status: normative for existing capability-owned Java MCP catalogs; the #610/#611 CDE target below governs their approved replacement.

The local `.dev/agent-contract/31-JAVA-STANDARD.md` through
`34-JAVA-MODEL-CONFIG-STANDARD.md` files are intentionally ignored by this
repository. This tracked document is the durable contract amendment for their
Catalog-Describe-Execute and structural-enforcement requirements; it must be
available to reviewers, CI, and future migration work without relying on a
developer-local file. The local `.dev/agent-contract` pack remains canonical
for session routing, search, proof, and change-impact rules; this document is
authoritative for the Java MCP architecture amendment. Both apply when a Java
MCP change is in scope, and local guidance must not weaken this contract.

## #610/#611 CDE target and transition

The approved alpha target is one aggregate `OperationDirectory` whose Java `OperationRegistration` is the canonical identity for Catalog, Describe, and Execute. Spring AI STDIO and `OperationMcpTools` remain thin transport/composition layers. Registrations should call substantive capability owners directly; a second capability-owned executable catalog, forwarding bridge, or historical alias table has no place in the final runtime unless a concrete product boundary still requires it.

The manifest and Java registrations must agree one-to-one: a present operation is supported, describable, and executable; an absent ID is unsupported. Assembly rejects mismatches. Remove `allowDeprecated`, deprecated execution outcomes, and XML `deprecated`, `replacement`, and `since` policy during the owning implementation. Keep `confirmed` and all current execution-safety behavior. XML supplies descriptions, argument prose, examples, tags, and discovery classification, with validated identity join keys. Java owns canonical identity, schema, requiredness, defaults, validation, safety, binding, and execution. Historical Tool/action mappings may support bounded development evidence but are not a final CDE route or an exhaustive parity-harness requirement. #611 removes them with the old Node/Java MCP routes after live acceptance of the replacement workflows.

The sections below describe the existing capability-owned catalog and its current build gate during development. Their old invocation-compatibility language does not override the approved #611 alpha replacement. They do not require adding another catalog, descriptor, or trace layer to CDE. The AST test permits cohesive private-to-private calls in its configured roots. It still enforces class and method bounds, nested-type limits, owner naming, and public/protected visibility rules. Do not add production classes merely to avoid private method decomposition. Retain independent method length, complexity, parameter, module, and STDIO checks. Explicit ticket acceptance criteria remain binding until amended.

## Module and compatibility boundaries

- `mcp-server/core` remains Spring-independent and must not depend on Spring
  Boot, Spring AI, the MCP SDK, Application packages, or Sidecar implementation
  internals.
- `mcp-server/application` owns Spring composition, MCP registration, transport
  request/response mapping, and dependency wiring. The dependency direction is
  `application -> core`.
- MCP adapters validate/map/normalize only. Core operation behavior, Artifact
  effects, Sidecar behavior, orchestration, and reporting stay behind their
  intentional Core boundaries.
- Existing Tool names, actions, and request envelopes are intentionally replaced by #611 CDE. Preserve substantive reason codes, Artifact semantics, Sidecar behavior, and runtime safety; use live MCP/Fixtures App evidence for the accepted Java workflows.

## Catalog-Describe-Execute

Each migrated capability owns one complete `OperationCatalog` for its closed
operation enum. The required route is:

```text
MCP Tool/action
  -> Application request mapper
  -> public Core Feature
  -> capability-owned OperationCatalog
  -> immutable OperationDescriptor
  -> concrete Operation.execute(typed request)
  -> purpose-owned collaborators
  -> Application response mapper
```

An `OperationDescriptor` is immutable metadata only and must contain:

- Tool and action identity;
- typed request and result ownership;
- concrete executable-owner identity; and
- trace metadata for the MCP adapter, request mapper, Core Feature, response
  mapper, focused proving evidence, side effect, and collaborator roles.

Descriptors must not contain executable lambdas, I/O, Spring types, or hidden
behavior. Concrete operation owners are public and remain `final` when they are
not an intentional extension point. Other public composition/adapter classes
must not be made non-public to hide ownership.

`OperationExposure` is the Application-provided description of the actual MCP
registration: Tool name, adapter class identity, and advertised actions. The
MCP annotation and exposure use the same compile-time Tool identity. Catalog
construction must validate that every descriptor matches that exposure, that
every closed action has exactly one operation, and that no Tool/action pair has
ambiguous ownership. Missing descriptors, duplicate registrations, and
unsupported actions fail closed deterministically before execution.

`OperationTraceInventory` may generate rows only from a validated catalog and
its `OperationExposure`; it must not use a duplicated inventory, runtime
classpath scan, or reflection scan. Each row covers the advertised MCP Tool,
action, request/result types, request mapper, Core Feature, capability catalog
dispatch owner, catalog descriptor, concrete executable owner, response
mapper, side effect, collaborator roles, and focused proving evidence. The
catalog dispatch owner is supplied by the capability-owned catalog as a
`Class<?>`, so the inventory cannot silently stop at the descriptor. A focused
Application test must keep the `@McpTool` registration, exposure, Feature
dispatch, and catalog inventory mechanically aligned.

## Structural enforcement

The migrated operation graph is subject to these hard limits:

- maximum 250 source lines per class;
- maximum 12 declared methods per class;
- no nested workflow types;
- no generic `Service`, `Manager`, `Helper`, `Util`, `Common`, or `Shared`
  owner names or package segments; and
- no public/protected visibility-laundering methods named as helpers,
  delegates, utility, internal, or pass-through code.

The enforcement implementation must inspect the Java compiler AST rather than
source regular expressions, so formatting, comments, strings, and equivalent
syntax cannot bypass the rules. It must include a compliant fixture for
cohesive private calls and both compliant and non-compliant fixtures for
method/class sprawl, nested types,
generic/shared owners, and visibility laundering. The affected Maven
test phase is build-enforced; a missing descriptor is a failing catalog test,
not a warning.

## Migration rules

1. Use TypeScript as a reference for substantive behavior while implementing Java CDE. #611 intentionally retires the old public Tool/action invocation contract; do not add a compatibility layer for it.
2. Define typed request/result ownership and a purpose-named concrete operation.
   A bounded operation table may serve multiple discriminator values when the
   execution algorithm is genuinely shared; do not create one implementation
   class per discriminator without distinct behavior.
3. Register the complete closed action set in the capability-owned catalog and
   pass the actual Application exposure into catalog construction.
4. Keep mapping and external effects in named, capability-owned collaborators;
   do not introduce generic service/manager/helper/util/common/shared layers.
5. Reuse focused capability evidence, keep MCP registration and inventory aligned, and record live MCP/Fixtures App outcomes for #611 acceptance. Do not build a new exhaustive three-way parity harness.
6. Add the capability's production roots to AST enforcement when its migration
   is accepted. Legacy capabilities remain on their approved path until their
   own migration story; this contract does not authorize a bulk refactor.

The reference implementation is documented in
[`java-mcp-operation-catalog.md`](java-mcp-operation-catalog.md).
