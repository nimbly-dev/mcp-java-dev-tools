# Tracked Java MCP Contract Amendment

Status: normative for migrated Java MCP capabilities.

The local `.dev/agent-contract/31-JAVA-STANDARD.md` through
`34-JAVA-MODEL-CONFIG-STANDARD.md` files are intentionally ignored by this
repository. This tracked document is the durable contract amendment for their
Catalog-Describe-Execute and structural-enforcement requirements; it must be
available to reviewers, CI, and future migration work without relying on a
developer-local file. The local `.dev/agent-contract` pack remains canonical
for session routing, search, proof, and change-impact rules; this document is
authoritative for the Java MCP architecture amendment. Both apply when a Java
MCP change is in scope, and local guidance must not weaken this contract.

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
- Existing Tool names, action values, request/result schemas, status and reason
  codes, Artifact semantics, Sidecar behavior, and TypeScript compatibility
  behavior remain unchanged until a separate parity-backed cutover.

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
- at most one private behavior method per class;
- no private-helper call chains;
- no nested workflow types;
- no generic `Service`, `Manager`, `Helper`, `Util`, `Common`, or `Shared`
  owner names or package segments; and
- no public/protected visibility-laundering methods named as helpers,
  delegates, utility, internal, or pass-through code.

The enforcement implementation must inspect the Java compiler AST rather than
source regular expressions, so formatting, comments, strings, and equivalent
syntax cannot bypass the rules. It must include both compliant and
non-compliant fixtures for private width/depth, method/class sprawl, nested
types, generic/shared owners, and visibility laundering. The affected Maven
test phase is build-enforced; a missing descriptor is a failing catalog test,
not a warning.

## Migration rules

1. Preserve the public Tool/action contract and keep TypeScript as the
   behavioral reference until parity evidence is complete.
2. Define typed request/result ownership and a purpose-named concrete operation.
   A bounded operation table may serve multiple discriminator values when the
   execution algorithm is genuinely shared; do not create one implementation
   class per discriminator without distinct behavior.
3. Register the complete closed action set in the capability-owned catalog and
   pass the actual Application exposure into catalog construction.
4. Keep mapping and external effects in named, capability-owned collaborators;
   do not introduce generic service/manager/helper/util/common/shared layers.
5. Add focused behavior/parity evidence, an MCP registration-to-exposure
   alignment test, and deterministic inventory assertions.
6. Add the capability's production roots to AST enforcement when its migration
   is accepted. Legacy capabilities remain on their approved path until their
   own migration story; this contract does not authorize a bulk refactor.

The reference implementation is documented in
[`java-mcp-operation-catalog.md`](java-mcp-operation-catalog.md).
