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

## MCPJVM-617 Probe and Project operation-directory migration

Ticket #617 migrates only the four Probe Config and four Project Context rows to
bounded operation-directory bindings. The released `artifact_management` Tool
and its legacy `artifactType`/`action`/`input` envelope remain unchanged.

| Routing source | Before #617 | After #617 | Disposition |
| --- | --- | --- | --- |
| `ArtifactProbeOperationBindings` | Four legacy Probe catalog rows | Removed | Rows are owned once by `ArtifactOperationRegistrations.legacyOperations`; no second Probe execution switch remains. |
| `ArtifactProjectOperationBindings` | Four legacy Project catalog rows | Removed | Rows are owned once by `ArtifactOperationRegistrations.legacyOperations`; no second Project execution switch remains. |
| `ArtifactOperationCatalog` | Composed separate Probe and Project tables | Composes the single eight-row compatibility table | The legacy Java Tool route remains available pending #611. |
| `ArtifactOperationRegistrations` | Generic unbounded bindings for all Artifact rows | Eight selected rows use bounded typed decode/encode, explicit non-cancellable continuation, closed schemas, and row-specific compatibility rules | The other 23 rows retain their prior binding behavior and remain outside #617. |
| `ArtifactOperationSchemas` | Schemas embedded in the registration owner | One bounded schema-only owner | Separates schema construction from execution routing and satisfies the production ownership limits. |
| Plan, Run, and Export binding tables | Existing legacy rows | Unchanged | No sibling-ticket execution migration was performed. |

The ticket's conservative routing measurement covers the eight existing
Artifact routing files named above (`ArtifactOperation`, the catalog and
registration owners, and the five family binding tables). It excludes imports,
comments, and schema-builder methods. On that fixed basis the #617 change is
**355 before -> 349 after (-6 lines)**. The schema code moved to
`ArtifactOperationSchemas`; it is excluded on both sides of the comparison and
does not conceal another execution table. The two removed Probe and Project
binding tables account for all superseded routes.

The executable proof is
`ArtifactProbeProjectOperationRegistrationTest`: it assembles all eight rows
strictly, compares canonical and legacy execution plus persisted state for each
row, and checks the generated compatibility inventory. Its
`generatesEightRowManifestAndTraceCompletionEvidence` case writes the assembled
descriptors and trace rows to
`mcp-server/core/target/mcpjvm-617-evidence/manifest.json` and
`mcp-server/core/target/mcpjvm-617-evidence/trace-inventory.json`; those files
are reproducible build evidence and are intentionally not another checked-in
inventory source. The raw public-schema
proof is `ArtifactProbeProjectLegacyStdioIT`; it checks every published branch,
field, required set, and default before executing the eight legacy invocations.

### Separately owned prerequisite repair

`mcp-server/core/src/test/resources/mcpjvm-613/baseline-operation-api.resolved.txt`
is not a #617 deliverable and is excluded from #617's allowed-path and
structural measurements. Commit `0755a0c` introduced that fixture for #613;
commit `c8dbf4d` removed it during #616 while leaving
`OperationRelocationApiCompatibilityTest` dependent on the resource. Its
working-tree restoration is therefore recorded as a separately owned #613/#616
prerequisite repair that restores the pre-existing Core compatibility gate. No
#617 production behavior depends on this fixture, and a commit or pull request
must identify its ownership separately from the #617 implementation.
