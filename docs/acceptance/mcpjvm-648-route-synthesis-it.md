# #648 fixture-backed Route Synthesis acceptance

Scope: Application packaged-JAR CDE integration tests, existing post-app fixture
support, and demonstrated corrections in the directly owning Core/Application
code. Baseline commit: `1900235e7f4b75e99ee394e57763292648f5fba4`.
Shared #646/#647 process, JSON-RPC, workspace, evidence, attach/deactivate and
cleanup support is reused. No Sidecar source or retired Node route is changed.

## Recorded reproductions before production corrections

`test/.tmp/648-reproduction.log` records these real packaged-MCP failures:

* `McpServerRouteSynthesisIT.overloadLineSelectsMatchingRoute`: inference validates
  the second overload's executable line, but `create_recipe` chooses the first
  overload (line 14 instead of 19) and consequently the wrong HTTP route.
  Expected contract: exact target/line selection and deterministic ambiguity,
  as retained in `tools/features/route-synthesis/support/recipe_generate/target_ambiguity.util.ts`
  and `shared/recipe_generation.ts`. Owner/path:
  `mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/synthesis/springhttp/SpringHttpSynthesizer.java`.
* `McpServerRouteSynthesisValidationIT.authenticationTemplatesAndUnsupportedProvidersRemainSanitized`:
  incomplete Basic credentials report `auth.status=needs_user_input` but
  `executionReadiness=ready`. Expected retained TS contract (`recipe_generation.ts`
  lines around `buildExecutionReadiness`): a report with
  `status=execution_input_required`, `reasonCode=auth_input_required` and
  `executionReadiness=needs_user_input`, preserving safe request/auth metadata.
  Owners/paths: Core `feature/routesynthesis/action/createrecipe/CreateRecipeResultAssembler.java`
  and `CreateRecipeTemplateModelBuilder.java`.

These are bounded contract-restoring corrections authorized by #645/#648.
Assertions are retained as packaged regression evidence.

`test/.tmp/648-runtime-reproduction.log` additionally records
`McpServerRouteSynthesisRuntimeIT.slowMappingsBodyIsBoundedAndServerRecovers`:
the mapping HTTP request declares a three-second timeout but a slowly streaming
body runs for 26.67 seconds, until the byte limit is reached. Cancellation already
passes. Expected contract: the existing three-second HTTP read budget also bounds
body consumption, closes the connection, retains the deterministic
`runtime_mappings_unreachable` report and permits subsequent real mapping reads.
Owner: Application's `RouteSynthesisRuntimeMappingsHttpAdapter.java` and
`RouteSynthesisRuntimeMappingsResponseReader.java`. A bounded virtual-thread read
uses the same absolute request deadline; timeout/interruption closes the owned
body and cancels the read task. The response byte ceiling is preserved.

## Acceptance repair batch: required auth and strict fallback

The user independently reproduced two P2 acceptance blockers after the initial
13-IT handoff. `test/.tmp/648-review-reproduction.log` records two new packaged
regression failures against that implementation before production repair:
`sourceAndOpenApiAuthenticationRequirementsBlockAnonymousRecipes` returns a
recipe instead of a required-input report for source `@PreAuthorize`;
`runtimeFirstFallbackValidatesStrictLineAgainstAttachedProbe` returns
`runtime_line_unresolved` for the same attached, valid line accepted under
`static_only`. The user's global OpenAPI bearer-security reproduction is also
included in the new auth scenario.

Expected contracts: retained TS
`tools/features/route-synthesis/support/recipe_generate/auth_resolve.util.ts`
infers controller security annotations and project OpenAPI requirements;
missing credentials produce `execution_input_required` / `auth_input_required`
with safe metadata. `runtime_first` may fall back to source mappings but must
validate the selected strict line against live Probe truth, without trusting
source-only line metadata or accepting invalid/unavailable runtime lines.
Owners: Core authentication resolver and create-recipe action/result assembler.
These are bounded corrections for the two reported acceptance blockers;
Sidecar and transport contracts are unchanged.

Authentication resolution now receives the selected source file, project root
and route path. Runtime mappings still select the HTTP route; source discovery
also supplies auth context when available. The concrete requirements reader
owns source/OpenAPI hint I/O while the existing resolver owns explicit input
selection and redacted metadata. Reads have a 512 KiB hard ceiling per file,
cooperative cancellation and contained OpenAPI paths. Unreadable/oversized
existing hints require input rather than producing an anonymous ready recipe.
The conservative controller-wide annotation and first-existing YAML hint
precedence follow the retained TS behavior. The running app owns authorization
enforcement. Existing custom resolver lambdas remain source-compatible; no MCP
field, schema, operation or Sidecar contract changes.

The strict fallback fix removes the premature source-metadata rejection and
uses the selected line's existing runtime capture/status evidence. Readiness
requires `lineResolvable=true` for line-probe intent in every discovery mode.
The obsolete pre-validation failure helper is deleted; the result assembler
owns the existing `runtime_line_unresolved` report.

## Interface-mapped controller authentication repair

The user identified a remaining P2 gap in runtime recipe authentication.
`test/.tmp/648-interface-auth-reproduction.log` records the new packaged-MCP
regression `interfaceMappedRuntimeRecipeRetainsControllerAuthenticationRequirements`
failing before production repair: copied `PostController` source has
`@PreAuthorize`, static handler discovery returns `mapper_plugin_unavailable`
because HTTP mappings live on `PostApi`, and runtime recipe creation nevertheless
returns a ready recipe instead of the required-input report. Expected contract:
controller security inference must depend on exact contained source resolution,
independently of static HTTP synthesis. Owner: Core `CreateRecipeAction` runtime
source-context resolution, using the existing workspace-bound Java source index.

Runtime recipe assembly now resolves the controller file directly by its FQCN
in `FileSystemJavaSourceDiscovery`, using the already captured workspace and
validated project/additional roots. A unique source match supplies authentication
context independently of annotations that synthesize HTTP handlers. No new
abstraction, interface-resolution engine or route fallback is introduced.
The existing runtime mappings remain the HTTP route authority. A Core regression
also makes static handler discovery fail if invoked while runtime recipe creation
still identifies the source security requirement.

## Persistence contract

The accepted #635 contract explicitly states that `create_recipe` does not persist
a product Artifact. Neither retained TS route action writes a report Artifact.
The tests retain bounded sanitized machine-readable assertion/outcome reports in
the existing scenario `evidence.json`; no new product Artifact format is invented.
Bug Drill and Bug Fix consumers explicitly prohibit persisted reports. Their
Tool/structured-field compatibility is the automated claim, not actual LLM Skill use.

## Prerequisites and selections

Java 21, Maven 3.8.6+, local loopback and Attach API access are required. No
JMeter, global Skill installation, user configuration or external service is
needed. Clean-checkout Sidecar prerequisites (reusable until changed):

```sh
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
```

Independent UT, without packaging/starting any fixture or attaching a JVM:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am test
```

Complete Route Synthesis capability, including all three new IT classes and all
their scenarios; the fixture profile builds only post-app and its dependencies:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-route,java-it-jvm-fixtures'
```

Separate one-scenario real functional smoke (discovery, generated transport,
fixture response/effect, strict hit and capture enrichment):

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-route-smoke,java-it-jvm-fixtures'
```

To reuse already packaged fixtures, omit `java-it-jvm-fixtures`. Missing JARs
fail explicitly; no assumptions/skips turn missing prerequisites into a pass.
For an individual scenario, the selector column below supplies the exact
Failsafe `Class#method` selection. For example:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerRouteSynthesisRuntimeIT#slowMappingsBodyIsBoundedAndServerRecovers'
```

Affected UT/static verification can be selected independently or appended to
either verification command without reducing its IT selection:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am test '-Dtest=*Route*Synthesis*Test,*RouteFailure*Test,*Synthesizer*Test,*InferTarget*Test,*ClassMethods*Test,*DiscoverHandlers*Test,*CreateRecipe*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

The explicit full Java IT command and its additional prerequisites remain in
[the shared contract](mcpjvm-646-java-it.md#commands). Its unfiltered `java-it`
selection includes these classes; neither full selection aliases the smoke
tag. The full repository suite was not run for this child. Surefire/Failsafe
discovery remains disjoint. No expensive fixture preparation runs in the UT
lane. `java-it-route` and `java-it-route-smoke` only select existing Failsafe
tags; they introduce no runner or production module.

## Behavior inventory and assertions

IDs below are stable Java test method names, also written as `scenarioId` in
machine-readable evidence. Every row executes the packaged application over
actual MCP STDIO. Rows with source-only inputs prove source synthesis, not
application execution. Only the named runtime/HTTP rows claim those effects.
The complete capability selection includes every row.

| Failsafe selector / scenario ID | Operations and meaningful variants | Exact semantic assertions and real effects |
| --- | --- | --- |
| `McpServerRouteSynthesisIT#discoveryRecipeTransportEffectAndCapture` | All four route operations; default static discovery; line-probe intent; bearer auth; template; confirmation; live capture | Resolves the immutable fixture snippet's actual executable line, selected FQCN/method and all three tag handlers; infers one candidate and exact strict key. Unconfirmed recipe/transport is blocked; transport denial leaves zero hits. Confirmed generated POST, with explicit fixture tenant/body input, reaches the existing downstream client, returns exact tenant/operation/request data, and produces a resolvable strict hit. A later recipe's capture ID equals the real Probe status capture ID. Credential sentinel is absent. Attach/deactivate and JSON-RPC-only stdout are asserted. This row is the explicit smoke subset. |
| `McpServerRouteSynthesisIT#overloadLineSelectsMatchingRoute` | `infer_target`, `create_recipe`; overloaded methods; line selection; regression ambiguity | Inference without a line reports ambiguity. The resolved second overload line selects `/route-selection/value/{value}` rather than `/plain`; actual GET response is `value-route:selected` and its strict line has a hit. Recipe without line disambiguation reports `target_ambiguous`. The added read-only controller is fixture support only. |
| `McpServerRouteSynthesisIT#runtimeMappingsModesAndRecovery` | `create_recipe`; runtime-only, runtime-first, default static; regression plan | Both live runtime modes discover the interface-mapped `PostController#getPost` route `/api/v1/posts/{postId}` and runtime provenance. Regression plan has zero Probe calls. Missing endpoint and refused connection return deterministic reports; runtime-first falls back to the exact static lock route; correcting the endpoint recovers a real runtime recipe. |
| `McpServerRouteSynthesisIT#ambiguityLimitsAdditionalRootsAndUnavailableRuntime` | `infer_target`, `class_methods`, `discover_handlers`; runtime unavailable, candidate limits, simple/FQCN class selection, additional roots | Unattached inference reports unresolved runtime, discovery is partial; attached inference retains ambiguity even when output is limited to one candidate. A generated second source root makes a simple class name ambiguous; exact FQCN succeeds. Deactivation makes subsequent inference unresolved. |
| `McpServerRouteSynthesisIT#probeRegistrySelectionLineValidationAndActuationPlanOptions` | `infer_target`, `create_recipe`; implicit registry target, explicit Probe ID precedence, unknown ID, simple class name, actuation plan options, invalid line | Generates registry through supported Artifact upsert. Implicit and explicit owned target resolve the same actual fixture line; explicit ID overrides invalid URL; unknown ID fails closed. Recipe carries false boolean and actuator ID in balanced prepare/cleanup instructions with four planned Probe calls. Synthesis does not execute/arm actuation (zero hits). Non-executable line is rejected. Actual actuation belongs to #647, whose existing runtime scenarios remain unchanged. |
| `McpServerRouteSynthesisRuntimeIT#mappingsSafetyFailureClassificationAndRecovery` | `create_recipe`; mappings URL safety, response failures, authorization, target/route ambiguity, byte ceiling | File/userinfo/query inputs and an unapproved host fail before exposure of credentials/destination. Real Spring health, plain text, missing route, rejected bearer credential, missing handler and overload mappings produce exact `runtime_mapping_not_found`, `runtime_mappings_invalid_payload`, `runtime_mappings_unreachable`, `runtime_mappings_unauthorized` and `runtime_mapping_ambiguous` reasons. Correct hints recover. A second packaged server with a 64-byte bound rejects the real Actuator response with exact limit evidence. |
| `McpServerRouteSynthesisRuntimeIT#slowMappingsBodyIsBoundedAndServerRecovers` | `create_recipe`; slow streaming HTTP body, timeout, connection cleanup, recovery | Reuses the existing delayed real HTTP target; no synthesized mapping/success payload. Response returns in under six seconds as `runtime_mappings_unreachable`, connection closes, and the same MCP server then discovers the actual Spring mapping. |
| `McpServerRouteSynthesisRuntimeIT#cancellationClosesMappingReadAndAllowsRecovery` | `create_recipe`; MCP cancellation during body read, recovery, shutdown | Observes connected HTTP request before cancellation and actual client disconnect afterward. Subsequent real fixture mapping succeeds. After draining STDIO on shutdown, no late cancelled response exists and fixture remains healthy. |
| `McpServerRouteSynthesisValidationIT#catalogDescribeAndMissingWorkspacePreserveCdeContract` | Catalog/Describe and all four Execute operations | Catalog contains exactly the four route IDs; each Describe has required project/schema/result fields and creation-only confirmation. Empty Roots produce exact `workspace_context_missing` through each owner, with no guessing from caller cwd. |
| `McpServerRouteSynthesisValidationIT#invalidInputsAndMissingTargetsFailDeterministically` | All four operations' shared input/path failures; recipe enum/line/actuation failures | Missing required project, unknown fields and 11 additional roots reject at schema boundary; absolute nonexistent/escaping project and invalid additional roots have exact owner-specific reasons. Missing target distinguishes inference, inventory, discovery and recipe reasons. Nonpositive line and invalid modes reject; missing strict line and actuation boolean have exact deterministic owner reports. Invalid-input output contains no supplied secret/path sentinel. |
| `McpServerRouteSynthesisValidationIT#authenticationTemplatesAndUnsupportedProvidersRemainSanitized` | `create_recipe`; no auth, incomplete Basic in both directions, complete Basic, bearer precedence, context path, known/unknown template tokens, missing method, JAX-RS/gRPC source placeholders | Exact auth status/strategy/missing fields and fail-closed readiness, normalized context path and token rendering; no credential values in output. Unsupported source frameworks return `mapper_plugin_unavailable` reports with no recipe action payload. Missing handler method returns `request_candidate_missing`. Placeholder classifications are covered, never operational provider coverage. |
| `McpServerRouteSynthesisValidationIT#sourceAndOpenApiAuthenticationRequirementsBlockAnonymousRecipes` | `create_recipe`; source security and global OpenAPI bearer requirements | Generated source inputs exercise `PreAuthorize`, `Secured`, `RolesAllowed`, and `SecurityRequirement`. Source `PreAuthorize` and global OpenAPI bearer requirements also exercise live runtime-mapping recipes. Without credentials, each returns the required-input report and non-ready auth metadata; explicit credentials restore readiness without exposing secrets. Generated annotations prove source inference, not authorization enforcement by the running fixture. |
| `McpServerRouteSynthesisValidationIT#interfaceMappedRuntimeRecipeRetainsControllerAuthenticationRequirements` | `create_recipe`; interface-mapped controller security under runtime-only and runtime-first | Copied `PostController` source has `PreAuthorize` while static handler discovery demonstrably fails because its HTTP mappings live on `PostApi`. Both runtime modes still report missing auth input; explicit credentials restore readiness, preserve the real `/api/v1/posts/{postId}` actuator mapping, and remain redacted. This proves independent source inference, with immutable running fixture sources. |
| `McpServerRouteSynthesisIT#runtimeFirstFallbackValidatesStrictLineAgainstAttachedProbe` | `create_recipe`; runtime-first fallback with strict line-probe intent | An attached executable target accepted under static-only yields the same route and a strict Probe plan when runtime mappings are missing or unreachable. Probe status verifies the exact key is resolvable. Invalid lines and a deactivated Probe still return `runtime_line_unresolved`. |
| `McpServerRouteSynthesisValidationIT#staticHttpVerbsAndAdditionalSourceRootsPreserveRequestTemplates` | `create_recipe`; GET/POST/PUT/PATCH/DELETE annotations, generic RequestMapping, duplicate additional roots, regression defaults, absent runtime class | Runtime-generated Java source inputs produce exact verb/path pairs from an additional root. POST/PUT/PATCH bodies default to `{}`; GET/DELETE bodies are null. Regression plans schedule zero Probe calls; uncompiled input classes explicitly report unavailable capture. These assertions prove source mapping/template behavior, not HTTP effects for generated classes. |
| `McpServerRouteSynthesisValidationIT#configuredExternalSynthesizerReturnsBoundedBlocker` | `create_recipe`; configured external module, sanitized plugin bootstrap | Dynamically generated private module path yields `synthesizer_not_installed`, `failedStep=plugin_bootstrap`, count-only evidence, with no module path/credential leak. External JS loading remains explicitly unsupported in the accepted Java runtime. |

Retained focused Java tests additionally protect registry API-version/plugin
validation, source ordering, ranking, template/runtime collaborators and shared
Core directory deadline/cancellation/output contracts. Relevant retained TS
references are `actions/infer_target.action.ts`, `actions/discover_handlers.action.ts`,
`actions/create_recipe.action.ts`, `shared/recipe_generation.ts`,
`support/recipe_generate/target_ambiguity.util.ts`, and `support/recipe_template.ts`.
They are read as behavioral references; no TS acceptance route is executed.

Skill consumers are Line Probe Run, Bug Drill and Bug Fix proposal. Discovery
carries the validated numeric line and Probe selector into recipe creation;
execution requires separately supplied concrete path/body/auth values and current
confirmation. The generated recipe is not itself execution proof. Runtime
capture and strict hits remain Sidecar-owned. Synthetic source cannot supply
runtime evidence. Resume/continuation state is not owned by this stateless
capability; retry after invalid input/unavailable runtime/timeout/cancellation is
covered. Suite continuation belongs to its other children.

## State, evidence, cleanup and compatibility

All sources/config/inputs and fixture JVM temp state are generated under the
dynamically resolved repository `test/.tmp/java-it-<run>/s-<scenario>/w-<workspace>`.
Original fixture sources are copied unchanged and source lines come from unique
snippets. Ports are allocated dynamically; only the owned PID/start identity is
attached. The fixture and attach helper share the scenario-owned temp directory.
Normal Maven compilation/package outputs remain in owning `target` directories.

After each scenario the shared extension retains only bounded allowlisted
`evidence.json` (scenario ID, operation/outcome/reason observations, prerequisite
versions, owned PID identities, cleanup outcome). It omits raw MCP payloads,
absolute paths, credentials, command lines and exception text. Assertion reports
are the ordinary Surefire/Failsafe XML in the module `target` directories.
Default teardown deletes scenario workspace files on success and failure and
stops all owned processes, including partial setup. `-DmcpItRetainFiles=true`
keeps scenario files for diagnosis but still terminates processes. Cleanup is
confined to verified run paths and owned process handles; no global product
state is read or changed.

No new public Tool, field/schema, provider, Artifact format or Sidecar route is
introduced. The three production corrections restore established intended
behavior; callers formerly receiving the wrong overload or unsafe ready state
now receive the correct route or existing deterministic report. The existing
mapping deadline also bounds body reads. CDE mapping/confirmation/redaction,
Spring-independent Core and thin Application ownership remain intact.
Gates C/D/G are supported by focused checks, live packaged STDIO, real Spring
mapping/transport and strict Probe/capture evidence; Gate E is checked against
the module README/POM and the shared test commands.

## Initial verification — 2026-10-11 (before acceptance repairs)

Tested the baseline working checkout plus this ticket's changes on Windows 11,
Corretto `21.0.12.1`, Maven `3.9.12`, server `0.1.9`, post-app
`0.1.0-SNAPSHOT`, packaged Sidecar/helper `0.1.8`. Reused unchanged repository
Sidecar artifacts; no Sidecar rebuild or source change was needed.

* Complete capability command above, with the affected `-Dtest=...` selection:
  **13 IT passed, zero failures/errors/skips**; **63 Core + 5 Application UT
  passed**. Checkstyle: zero violations; PMD and dependency/module enforcement
  passed. Local log: `test/.tmp/648-capability-final.log`. Every inventory row
  is **covered** for its explicitly stated assertions, including unsupported
  classifications. No missing/partial scenario in that inventory is counted
  as covered.
* Separate smoke command, with the same UT selection, executed from
  `mcp-server/application` using `-f ../pom.xml`: **one IT passed**, exactly
  `discoveryRecipeTransportEffectAndCapture`; other expensive capability
  scenarios were unselected. Log: `test/.tmp/648-smoke.log`.
* Independent affected UT command above: **68 passed**, zero failures/errors/
  skips. Log inspection confirms no Failsafe, fixture-preparation or Spring
  packaging phase ran. Log: `test/.tmp/648-unit-only.log`.
* Complete capability evidence:
  `test/.tmp/java-it-5268404063557570784/s-*/evidence.json`: **13 passed,
  13 cleanup successes, zero remaining workspaces, zero live owned PIDs**.
  Smoke evidence likewise reports success and cleanup. Machine-readable
  aggregate: `test/.tmp/648-verification.json`. These generated files are
  gitignored, with no committed raw MCP dump or generated workspace.
* `git diff --check` passed. README/profile commands and the CDE field
  descriptions in `docs/data-fields/README.md` were checked against the actual
  schemas/registrations and passing responses. Historical invocation routes
  remain intentionally retired under #611/#644. This is no claim that parent
  #645's other capability children or the full repository suite are complete.

The final smoke-tested packaged server SHA-256 is
`A8DDE689FDBF6AAB3197E77AFE37BFFF0F24D09558EDD0CD85B560B9E3B6BF4E`.
Packaged Sidecar SHA-256:
`FF4AB9479BB624954A5BF1862F3FC9D44BD5AA15513002A051EF01FD78FFA26E`;
helper SHA-256:
`7D4CD8A984D08F4FBF3F1CA9C6152F3766E508F6D17B4A0391C4CB8ABDCA0E6D`.
Other OS/platform executions were not repeated for this child.

## First acceptance repair verification — 2026-10-11

Both reported P2 blockers are repaired and covered. Final verification used the
same Windows/Java/Maven environment and unchanged packaged fixture/Sidecar
prerequisites, with this exact affected command:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-route' '-Dtest=*Route*Synthesis*Test,*RouteFailure*Test,*Synthesizer*Test,*InferTarget*Test,*ClassMethods*Test,*DiscoverHandlers*Test,*CreateRecipe*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

`test/.tmp/648-review-capability-final.log` records **BUILD SUCCESS** at
`2026-10-11T21:00:40+08:00`: **15 IT passed** (6 route + 3 runtime + 6 validation),
**74 UT passed** (69 Core + 5 Application), zero failures/errors/skips.
The six added UT assertions cover inferred bearer/Basic/cookie requirements,
credential-type mismatch, endpoint path scoping, qualified source annotations,
redaction and the hint read ceiling. Checkstyle has zero violations; PMD,
dependency convergence and module-boundary enforcement passed. Gates C/D/G are
supported by the complete packaged-MCP run, including the existing functional
smoke scenario. Gate E is supported by the updated field and acceptance docs.
The initial separate smoke/independent-UT selection evidence remains recorded
above; their configuration is unchanged. No full repository suite or other OS
execution was performed for the repair batch.

Final sanitized evidence:
`test/.tmp/java-it-10067425949902690257/s-*/evidence.json`:
**15 passed, 15 cleanup successes, zero remaining workspaces and zero live owned
PIDs**. Machine-readable repair aggregate:
`test/.tmp/648-review-verification.json`. Expected pre-repair failures remain in
`648-review-reproduction.log`; the two targeted repaired scenarios also passed
in `648-review-repair.log` before the final capability run.

The final tested server JAR SHA-256 is
`C983C845C5DD04ED3143649656F0B09DCD1B2B3B7A90924B0372D54277EF31A1`.
This supersedes the initial server JAR hash for the repaired implementation.
Sidecar/helper hashes remain unchanged. `git diff --check` passed.

## Interface-controller repair verification — 2026-10-11

The remaining interface-mapped controller finding is repaired. The new inventory
contains 16 scenarios. The four affected packaged-MCP scenarios were reverified
on the updated JAR; previous evidence is retained for unchanged paths, without
claiming a fresh full-capability run. Exact command:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-route' '-Dit.test=McpServerRouteSynthesisValidationIT#interfaceMappedRuntimeRecipeRetainsControllerAuthenticationRequirements+sourceAndOpenApiAuthenticationRequirementsBlockAnonymousRecipes,McpServerRouteSynthesisIT#runtimeMappingsModesAndRecovery+runtimeFirstFallbackValidatesStrictLineAgainstAttachedProbe' '-Dtest=*Route*Synthesis*Test,*RouteFailure*Test,*Synthesizer*Test,*InferTarget*Test,*ClassMethods*Test,*DiscoverHandlers*Test,*CreateRecipe*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

`test/.tmp/648-interface-auth-repair.log` records **BUILD SUCCESS** at
`2026-10-11T21:10:13+08:00`: **4 IT passed, 75 UT passed** (70 Core + 5 Application),
zero failures/errors/skips. The four integration scenarios cover the new
interface-mapped auth regression in both runtime modes, previous source/OpenAPI
auth cases, runtime mapping selection/recovery, and the resolved strict-line
fallback. The added Core test proves auth inference without static handler
discovery. Checkstyle has zero violations; PMD, dependency convergence and
module-boundary enforcement passed. Gates C/D/G use these packaged-MCP and
affected-unit results; Gate E uses the updated acceptance and field descriptions.
Public schemas, report reason codes, route selection, Sidecar behavior and
verification profiles retain their established contracts.

Evidence: `test/.tmp/java-it-15611761340133150563/s-*/evidence.json`:
**4 passed, 4 cleanup successes, zero remaining workspaces and zero live owned
PIDs**. Aggregate: `test/.tmp/648-interface-auth-verification.json`.
Expected pre-fix failure: `test/.tmp/648-interface-auth-reproduction.log`.

Latest tested server JAR SHA-256:
`B80B7CB24B726E0E2C60614EF164715D4A8C14A7B3932A7B7135198537C6DD7F`.
This supersedes the earlier server hashes for the current working checkout.
Windows/Java/Maven and Sidecar/helper prerequisites remain as recorded above.
`git diff --check` passed. No full repository suite was run.
