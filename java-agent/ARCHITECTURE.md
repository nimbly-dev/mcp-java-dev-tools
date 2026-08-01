# Java Agent Architecture Inventory

This document is the Phase 0 inventory and guardrail for the capability-oriented
Java Sidecar Agent migration. Maven modules are the authoritative ownership
boundaries. Package moves must preserve the existing agent manifest entry point,
HTTP contracts, Probe behavior, Strict Line Key format, and deterministic reason
codes unless a separate behavior change is explicitly approved.

## Module ownership

| Module | Current responsibility | Intended public surface | Allowed direction |
| --- | --- | --- | --- |
| `core-probe-runtime` | Probe state, actuation, line-hit state, and correlation state | `agent.runtime.api` runtime and correlation contracts | Foundation; no dependency on feature modules |
| `core-probe-debug` | Capture and failure-analysis capabilities | `agent.debug.api` capture/failure contracts | Depends on runtime API only |
| `core-probe-instrumentation` | Byte Buddy visitors, advice, and transformation setup | `agent.instrumentation.application` installation surface | Depends on runtime/debug APIs; never HTTP or mapper modules |
| `core-probe-control-http` | Embedded HTTP control adapter, endpoint handlers, parsing, and response mapping | `ProbeHttpServer`, HTTP protocol models, and capability handlers | Depends on runtime/debug/profiler APIs only |
| `core-probe-profiler` | Profiler abstraction and providers | `agent.profiler.api` profiler operations, requests, and results | Depends on runtime API only |
| `core-probe` | Agent bootstrap, lifecycle delegation, and shaded assembly | `com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent` | Assembly/bootstrap depends on runtime, debug, instrumentation, control-http, profiler |
| `core-jvm-attach` | Local JVM attach operation | Attach command/API | Must not depend on the bundled agent implementation |
| `core-entrypoint-mapper` | Framework-neutral Request Mapper engine and HTTP template materialization | `requestmapping.api` contracts | Must not depend on probe modules or adapters |
| `mappers-adapters/*` | Framework-specific Request Mapper extraction | Adapter SPI implementations | Depends on `core-entrypoint-mapper` public API only |

## Current migration inventory

The capability split is now active in the changed surfaces:

- `core-probe` now contains lifecycle orchestration and bootstrap configuration;
  Byte Buddy transformer installation and correlation advice live in
  `core-probe-instrumentation`.
- `core-probe-runtime` exposes `runtime.api.RuntimeApi` and
  `runtime.api.CorrelationApi`; stateful runtime implementation and legacy
  bootstrap classes remain behind that boundary.
- `core-probe-debug` exposes `debug.api.DebugApi`; capture and failure
  implementation packages remain private capability owners.
- `core-probe-profiler` exposes `profiler.api.ProfilerApi`; provider and model
  implementation classes remain behind that boundary.
- `core-probe-instrumentation` keeps the installation surface in
  `instrumentation.application` and Byte Buddy advice/ASM visitors in
  `instrumentation.adapter.bytebuddy`.
- `core-probe-control-http` keeps `ProbeHttpServer` lifecycle and route
  registration only. Endpoint handlers are organized under capability packages;
  `ProbeHttpJson`, `ProbeHttpMapper`, and profiler response policy are explicit
  adapter support surfaces.
- `core-entrypoint-mapper` exposes the adapter-facing SPI, AST context, index,
  resolved mapping, and HTTP materializer from `requestmapping.api`. Active
  Spring adapter imports are API-only, and ServiceLoader registration uses the
  API SPI name. The old `extractor.MappingExtractor` interface and its former
  parameter/result FQCNs are retained as a binary-compatible SPI. The registry
  converts legacy providers to the canonical API at the module boundary.
- `ProbeLifecycle`, HTTP endpoint handlers, instrumentation, and debug runtime
  consumers use the named runtime/debug/profiler API surfaces. Remaining
  implementation imports are inside their owning modules or compatibility
  bridges.
- Remaining flat implementation packages in runtime/debug/profiler are
  documented follow-up candidates; they are not re-exported as Request Mapper
  adapter contracts.
- The example, JAX-RS, and gRPC adapter directories are intentionally not all
  active Maven modules; Spring HTTP is the only active adapter today.

## Package rules

Packages identify capability first. Generic `service`, `serviceImpl`, `common`,
`shared`, and `util` packages are not permitted for new Java-agent code.

Technical subpackages are allowed only when they clarify ownership:

- `api`: stable contracts intentionally imported by another module.
- `model`: immutable values local to one capability.
- `application`: named orchestration operations.
- `adapter`: Byte Buddy, HTTP, Jackson, filesystem, Attach API, or other boundary integrations.
- `state`: mutable and concurrency-sensitive state.
- `serialization`: protocol materialization only.
- `internal`: small private implementation groupings.

The target rule is that cross-module imports use only the owning module's
declared public API. Checkstyle enforces that rule for active Request Mapper
adapters and for runtime/debug/profiler consumers; capability owners are
explicitly exempted only for their own implementation packages. Compatibility
bridges are boundary-owned and retain only the old binary contracts required
for external providers. A package rename must move the implementation and
update its callers; duplicate implementation copies are prohibited.

## Migration sequence

1. Inventory module dependencies and package/import ownership.
2. Keep `ProbeAgent` limited to `premain`/`agentmain` delegation while extracting
   lifecycle orchestration behind the existing manifest FQCN.
3. Formalize runtime, instrumentation, debug, profiler, and control-http APIs.
4. Separate HTTP parsing, routing, endpoint handlers, and response mapping.
5. Complete Request Mapper API isolation and adapter migration.
6. Remove obsolete package paths and add package/import architecture checks.

Steps 2 through 6 are implemented for the current bootstrap, HTTP,
instrumentation, debug/profiler consumers, and active Request Mapper adapter
surfaces. Compatibility bridges preserve existing agent behavior and legacy
mapper providers while the canonical API packages become the only
cross-module source imports.

Every step requires focused Maven evidence. Runtime-facing steps additionally
require the applicable Probe, HTTP, include/exclude, or Request Mapper evidence.
