# Artifact Management structural inventory

This is the before/after inventory for the #603 implementation against the
parent Catalog-Describe-Execute requirements in #601. The baseline is the
unchanged repository commit `00838bd61921de0615bdeb3977f62c2db99bf67b`.

## Public contract

The released Tool remains `artifact_management`. The closed Artifact/action
allowlist remains 31 pairs, with the following single executable owner for
each row:

| Artifact family | Allowed actions | Executable owner | Count |
| --- | --- | --- | ---: |
| Probe Config | `read`, `validate`, `upsert`, `reload` | `ProbeConfigOperations` | 4 |
| Project Context | `read`, `validate`, `upsert`, `list` | `ProjectContextOperations` | 4 |
| Performance Plan | `read`, `validate`, `upsert`, `list` | `PlanOperations` | 4 |
| Regression Plan | `read`, `validate`, `upsert`, `list` | `PlanOperations` | 4 |
| Security Plan | `read`, `validate`, `upsert`, `list` | `PlanOperations` | 4 |
| Run Result | `read`, `upsert`, `list`, `rebuild`, `backfill`, `cutover`, `query`, `cleanup` | `RunResultOperations` | 8 |
| Execution Export | `read`, `list`, `generate` | `ExecutionExportOperations` | 3 |
| **Total** |  |  | **31** |

`ArtifactManagementMcpSchema` retains the existing branch and input shapes.
`ArtifactManagementMcpToolTest` compares those shapes with the canonical schema
builder, and the raw `tools/list` assertion compares the published schema's
SHA-256 fingerprint with the fingerprint produced from that builder.

## Structural before/after

| Area | Baseline at the commit above | Current implementation | Proof of the change |
| --- | --- | --- | --- |
| Per-action Artifact wrappers | 31 `action/impl/*Action.java` wrappers plus `ArtifactManagementActionHandler` | 0 wrapper files and no handler; dispatch is catalog-owned | `ArtifactManagementFeatureRoutingTest` and the Core source inventory |
| Spring Artifact composition | 31 action Bean methods and handler aggregation | Five family-owner Beans, one catalog Bean, one Feature Bean, plus infrastructure Beans | `ArtifactManagementConfiguration` and Application context verification |
| Catalog ownership | No complete Artifact operation catalog | `ArtifactOperationCatalog` registers all 31 closed actions exactly once through five explicit family binding tables | `ArtifactManagementFeatureRoutingTest.generatedInventoryContainsExactlyTheSevenFamiliesAndThirtyOnePairs` |
| Executable owner evidence | Descriptor/owner alignment was not independently proven | Each registration receives a declared concrete family-owner class, the injected owner instance, and a typed method reference; the descriptor uses the declared class while the operation identity is derived from the actual instance, and `OperationCatalog` rejects mismatches | `ArtifactOperationTest.rejectsDescriptorOwnerThatDisagreesWithInjectedFamilyOwner` and the 31-row routing inventory |
| Execution export root | `ExecutionExportArtifacts.java`: 964 lines with mixed read, generation, rendering, filesystem, and script behavior | `ExecutionExportOperations.java`: 37-line public family facade delegating to purpose-owned reader, generator, package, replay, script, renderer, and artifact writers | `JavaMcpArchitectureEnforcementTest` covers the migrated export graph |
| SQLite run-state root | `SqliteRunStateStore.java`: 1,162 lines mixing schema, locking, scan, projection, query, backfill, cutover, cleanup, and response shaping | `SqliteRunStateStore.java`: 89-line public facade over named database, lock, JSON, reader, projection, query, lifecycle, and retention components | `SqliteRunStateStoreTest` plus `JavaMcpArchitectureEnforcementTest` |

The remaining `ExecutionExportWorkload`, `ExecutionExportPerformanceArtifacts`,
and `ExecutionExportPortableSidecar` classes are existing purpose-owned
collaborators with behavior-specific contracts. They are not hidden behind the
public family owner or Spring composition, and the new facade does not add a
second generic service/helper layer.

## Generated inventory source

The production catalog is the only operation inventory source. Its
`traceInventory()` is generated from the validated registrations and the
Application-provided `OperationExposure`; no duplicated hand-maintained
inventory, classpath scan, or reflection scan is used. The routing test asserts
all 31 action rows, the seven family types, and the concrete owner/method for
every row.

The runtime route is:

```text
ArtifactManagementMcpTool
  -> ArtifactManagementFeature
  -> ArtifactOperationCatalog
  -> ArtifactOperation
  -> one of the five family owners
```

The Application adapter remains responsible only for MCP registration,
request mapping, boundary handling, Feature invocation, and response mapping.
The reusable Application composition standard remains owned by #605; this
change adds only Artifact-specific family wiring.

## Verification evidence

- Core operation, routing, export, SQLite, and catalog tests are run with the
  bounded Core Maven commands for the affected classes.
- `JavaMcpArchitectureEnforcementTest` uses the Java compiler AST and covers
  the operation package, export migration graph, and SQLite migration graph.
- `ArtifactManagementMcpToolTest` verifies the 31 public schema branches and
  required compatibility fields.
- `McpServerStdioIT` verifies the raw `tools/list` schema fingerprint and
  exercises each Artifact family, invalid routing, and unsupported routing.
- `ArtifactManagementParityMatrixTest` compares Java inputs, outputs,
  reason-code evidence, query bounds, export defaults, and Skill Workflow
  consumption against the released TypeScript sources.

This document is evidence for the working-tree change. The repository was
already partially staged before implementation; the index must be reviewed as
a whole before any commit, and this inventory must not be committed alone.
