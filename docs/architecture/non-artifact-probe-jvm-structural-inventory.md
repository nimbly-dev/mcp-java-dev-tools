# Non-Artifact rollout group 1 structural inventory

This document records rollout group 1 of #606: Probe and JVM Lifecycle. It is
bounded to the two capabilities and does not claim completion of the remaining
#606 rollout groups.

The baseline is the repository commit `29f3b70353efaf81c87c83c8d2260fb888533a3`.
The counts below are source-shape counts for the changed capability roots; the
substantive action implementations remain unchanged and are explicitly
retained as the executable owners.

## Before and after

| Capability | Inventory item | Before | After | Evidence/interpretation |
| --- | --- | ---: | ---: | --- |
| Probe | substantive action implementation files | 7 | 7 | `Probe*Action` implementations remain the behavior owners |
| Probe | typed handler aliases | 1 | 1 | `ProbeActionHandler` remains the typed Core boundary |
| Probe | capability `EnumActionDispatcher` references | 1 | 0 | `DefaultProbeFeature` now executes through `ProbeOperationCatalog` |
| Probe | constructor-only delegation wrappers | 0 | 0 | no wrapper was removed or added |
| Probe | private behavior methods in the changed Feature/catalog roots | 0 | 0 | compiler-AST architecture enforcement |
| Probe | `DefaultProbeFeature` source lines / declared behavior methods | 49 / 1 | 56 / 1 | constructor compatibility is retained; execution ownership moved to the catalog |
| Probe | catalog descriptors and executable owners | 0 | 7 | closed `ProbeAction` enum is validated at catalog construction |
| JVM Lifecycle | substantive action implementation files | 3 | 3 | `ListJvmsAction`, `AttachAction`, and `DeactivateAction` remain behavior owners |
| JVM Lifecycle | typed handler aliases | 1 | 1 | `JvmLifecycleActionHandler` remains the typed Core boundary |
| JVM Lifecycle | capability `EnumActionDispatcher` references | 1 | 0 | `DefaultJvmLifecycleFeature` now executes through `JvmLifecycleOperationCatalog` |
| JVM Lifecycle | constructor-only delegation wrappers | 0 | 0 | no wrapper was removed or added |
| JVM Lifecycle | private behavior methods in the changed Feature/catalog roots | 0 | 0 | compiler-AST architecture enforcement |
| JVM Lifecycle | `DefaultJvmLifecycleFeature` source lines / declared behavior methods | 25 / 1 | 35 / 1 | constructor compatibility is retained; execution ownership moved to the catalog |
| JVM Lifecycle | catalog descriptors and executable owners | 0 | 3 | closed `JvmLifecycleAction` enum is validated at catalog construction |

The new capability-owned catalogs are 83 lines each, the operation bindings
are 68/69 lines, and their effect classifiers are 20/18 lines. Each generated
descriptor names the existing concrete action owner, and each catalog rejects
missing or duplicate closed-enum ownership through the shared
`OperationCatalog` contract.

Probe effect metadata distinguishes `CAPTURE` as `probe_capture_read` because
the action retrieves an existing Sidecar capture. `PROFILER` retains
`probe_artifact_write` because its output store writes a downloaded profile;
the endpoint reset and actuation actions retain their write classification.

## Ownership and residual scope

The Application configs now assemble one catalog per capability from the
existing action beans, provide `OperationExposure` and trace metadata, and then
construct the public Feature from that catalog. The MCP adapters expose the
same stable Tool names and action values through the registration metadata.
The existing shallow adapter mapping and boundary behavior remain unchanged.

This group intentionally does not rewrite the action implementation internals,
the Route Synthesis/Failure Analysis/Transport group, suite implementations,
or Execution Orchestration. Their private-method and class-size reductions are
residual work for the later #606 rollout groups. `EnumActionDispatcher` remains
available to those not-yet-migrated capabilities and is not changed here.

## Generated inventory and enforcement

`ProbeOperationCatalog.traceInventory()` and
`JvmLifecycleOperationCatalog.traceInventory()` derive rows from the validated
registrations and the Application `OperationExposure`; there is no duplicate
inventory or classpath scan. `JavaMcpArchitectureEnforcementTest` now includes
both operation packages and enforces the existing class, method, private-width,
helper-chain, nesting, generic-owner, and visibility-laundering rules.

Focused catalog tests prove closed action completeness, descriptor order,
effect classification, executable delegation, and fail-closed incomplete
registration. Application configuration and MCP adapter tests prove the
registration metadata and existing TypeScript-compatible request/response
paths.
