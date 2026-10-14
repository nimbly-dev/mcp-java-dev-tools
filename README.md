# mcp-java-dev-tools

[![JDK](https://img.shields.io/badge/JDK-21%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Java Agent Target](https://img.shields.io/badge/Java%20Agent%20Target-21-ED8B00?logo=openjdk&logoColor=white)](https://maven.apache.org/)
[![MCP Badge](https://lobehub.com/badge/mcp/nimbly-dev-mcp-java-dev-tools?style=flat)](https://lobehub.com/mcp/nimbly-dev-mcp-java-dev-tools)

**MCP Java Dev Tools** bridges agentic coding tools and live Java runtime behavior through a lightweight sidecar agent.

Static analysis only gets you so far. By attaching directly to a running JVM, this tool surfaces bytecode-level runtime signals that static analysis alone can't see â€” enabling probe-verified inspection, targeted regression checks, runtime-path validation, and deterministic debugging workflows.

The runtime agent is built with ByteBuddy and works alongside JDWP rather than replacing it. On top of the probe layer, the system adds framework-aware data synthesis and strict, fail-closed tool contracts â€” so agent orchestrators can make decisions grounded in actual runtime proof, not inference.

The current focus is HTTP entrypoints. Non-HTTP protocol support is on the horizon but not yet implemented â€” it will need concrete models and validation targets before the core contracts can be generalized.

For operator workflows and end-to-end execution flows, see [docs/how-it-works/README.md](./docs/how-it-works/README.md).

---

## Requirements

| Requirement | Version  | Purpose |
| ----------- | -------- | ------- |
| JDK         | `21`     | MCP server and Sidecar Agent runtime |
| Maven       | `3.8.6+` | Java build and verification |

Java unit tests and real process integration tests have independent lanes. See
[Java IT commands, prerequisites and coverage](docs/acceptance/mcpjvm-646-java-it.md) and
[Real JVM/Probe capability acceptance](docs/acceptance/mcpjvm-647-jvm-probe-it.md)
for UT, on-demand smoke, complete capability and full opt-in selections. The
Java server exposes `mcp-java-dev-tools://status` for safe runtime/workspace
metadata alongside the three CDE Tools.

For the complete HTTP/HTTPS transport capability and its separate smoke lane,
see [Transport authentication, cancellation and coverage](docs/acceptance/mcpjvm-651-transport-it.md).

For real JMeter/builtin workloads, profiler evidence and Performance recovery,
see [Performance capability acceptance](docs/acceptance/mcpjvm-653-performance-it.md).

---

## Build

```text
bash ./scripts/package-java-mcp-server.sh
# Windows PowerShell: .\scripts\package-java-mcp-server.ps1
```

This verifies the Java MCP reactor and produces the deployable runtime:

- **MCP server** -> `mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar`
- **Packaged lifecycle helper** -> `mcp-server/application/target/sidecar/jvm-attach-helper.jar`
- **Packaged Sidecar Agent** -> `mcp-server/application/target/sidecar/sidecar-agent.jar`

Legacy TypeScript sources may be inspected during migration to identify behavior
that must be implemented in Java. They are not a runtime, test, or acceptance
path for the packaged MCP server. See [mcp-server/README.md](./mcp-server/README.md)
for runtime and module boundaries.

## Installation

### Installer

Installer flow is split into install and update scripts (Codex and Kiro skills).

```bash
./scripts/install.sh
```

This installs the default skill set:

- `mcp-java-dev-tools-line-probe-run`
- `mcp-java-dev-tools-regression-suite`
- `mcp-java-dev-tools-regression-plan-crafter`
- `mcp-java-dev-tools-regression-result`
- `mcp-java-dev-tools-issue-report`
- `mcp-java-dev-tools-bug-drill`
- `mcp-java-dev-tools-bug-fix`
- `mcp-java-dev-tools-failure-lens`
- `mcp-java-dev-tools-probe-registry-manager`

To update/overwrite existing installed skills (and add missing new skills):

```bash
./scripts/update.sh
```

Both scripts:

- package and verify the Java MCP server and its Sidecar artifacts
- sync shipped skills into the target client skill directory
- generate client-specific `java -jar` MCP configuration when requested

Kiro and Claude Code behavior during install/update:

- stale managed skills matching `mcp-java-dev-tools-*` are detected and can be deleted interactively
- installed managed skills are validated after sync (`SKILL.md` + expected folder presence)
- restart/reload guidance is printed so the visible tool/skill list refreshes from the synced skill directory

MCP server configuration can be skipped:

```bash
./scripts/install.sh --client codex --no-configure-mcp
# Or: ./scripts/install.sh --client claude --no-configure-mcp
```

The installer auto-detects the packaged server JAR. To select one explicitly:

```bash
./scripts/install.sh --server-jar /absolute/path/to/mcp-java-dev-tools-server-0.1.9.jar
```

### Spring Integration Launcher

Use the helper launcher to run a Spring app with auto-inferred Java agent include scope and probe port:

```bash
./spring-integration/run-spring-app-with-mcp.sh
```

Behavior:

- prompts for Spring project absolute path, app port (default `8080`), and optional JDWP port
- infers include package from `src/main/java`
- assigns probe port starting at `9173` and increments if occupied
- opens a new Git Bash window and starts the Spring app with `JAVA_TOOL_OPTIONS` including `-javaagent`

### Manual Setup

Configure the MCP client to launch the packaged Java STDIO server:

```toml
[mcp_servers.mcp-java-dev-tools]
command = "java"
args = ["-jar", "C:\\absolute\\path\\to\\mcp-java-dev-tools-server-0.1.9.jar"]
```

Keep the JAR beside its generated `sidecar/` directory. The server reserves
stdout for MCP JSON-RPC and writes diagnostics to stderr.

#### Java Agent Setup

The target JVM must run on **Java 21 or newer**. Java 17 is unsupported because the Java reactor
and dynamic lifecycle helper compile for Java 21.

Add the following as a JVM argument when launching your application, replacing `{desktopName}`:

```text
-javaagent:C:\Users\{desktopName}\repository\mcp-java-dev-tools\java-agent\core\core-probe\target\mcp-java-dev-tools-agent-0.1.8.jar=host=0.0.0.0;port=9191;exclude=com.nimbly.mcpjavadevtools.agent.**,**.config.**,**Test
```

> **Tip:** The `include` filter is optional. If omitted, the agent infers an include scope from startup command metadata (`sun.java.command`), usually the startup class package (for example `com.acme.app.**`). Set `include` explicitly when inference is ambiguous or too broad.
>
> `include` supports comma-separated basepaths:
>
> - package globs (for example `com.thirdparty.service.**`)
> - exact class FQCNs (for example `com.example.ApiClass`)
> - mixed module/class targeting in one value (for example `com.example.app.**,com.example.api.**,com.thirdparty.SomeClass`)

To confirm the agent is instrumenting your classes, check the startup logs for lines like:

```txt
[mcp-probe]: com.yourpackagename.yourclassname
```

If you don't see your classes listed, check your `include` filter.

#### Dynamic Attach (Java 21+)

The separate lifecycle helper discovers local JVM PIDs and dynamically loads only an agent JAR
with the expected Sidecar Agent manifest. It requires an exact PID and explicit confirmation;
discovery is intentionally unverified and does not expose target command lines or properties.

```powershell
java -jar java-agent\core\core-jvm-attach\target\mcp-java-dev-tools-core-jvm-attach-0.1.8.jar discover
java -jar java-agent\core\core-jvm-attach\target\mcp-java-dev-tools-core-jvm-attach-0.1.8.jar attach --pid {pid} --expected-process-start-epoch-ms {process-start-epoch-ms} --agent-jar {absolute-agent-jar-path} --confirm true
java -jar java-agent\core\core-jvm-attach\target\mcp-java-dev-tools-core-jvm-attach-0.1.8.jar deactivate --pid {pid} --expected-process-start-epoch-ms {process-start-epoch-ms} --agent-jar {absolute-agent-jar-path} --confirm true
```

On Java 21, dynamic agent loading succeeds by default but emits the JEP 451 warning.
`-XX:+EnableDynamicAgentLoading` suppresses that warning only when operators explicitly choose it.
`-XX:-EnableDynamicAgentLoading` and `-XX:+DisableAttachMechanism` return Fail-Closed lifecycle
results. `VirtualMachine.detach()` closes the helper session; it does not unload agent classes.
Deactivation disables Sidecar Agent-owned instrumentation and reports non-restorable classes.

For local CI, declare `sidecarLifecycle.activation` in the project Artifact instead of attaching through a shell wrapper. The supported startup is one direct `java`/`java.exe` invocation with one relative `-jar` path; the execution orchestrator owns attach, canonical Probe verification, resume continuity, cancellation cleanup, and terminal deactivation under one `suiteRunId`.

```json
{
  "sidecarLifecycle": {
    "activation": "dynamic_attach_local",
    "targetStartupName": "orders-service",
    "probeId": "orders-service",
    "verifyProbeAfterAttach": true
  }
}
```

---

<details>
<summary><strong>IntelliJ IDEA â€” Step by Step</strong></summary>

1. Open **Run > Edit Configurations...** from the top menu
2. Select the run configuration for your target application (or create one if it doesn't exist)
3. Expand the **Modify options** dropdown and enable **Add VM options** if it isn't already visible
4. In the **VM options** field, paste the full `-javaagent:...` argument from above
5. Click **Apply**, then **OK**
6. Run your application normally â€” the agent attaches on startup

**Finding the JAR path:** If you're unsure of the absolute path, right-click the agent JAR in the Project panel and choose **Copy Path > Absolute Path**.

> On Windows, use backslashes in the path (`C:\Users\...`). On macOS/Linux, use forward slashes (`/home/...` or `/Users/...`).

</details>

---

<details>
<summary><strong>Eclipse â€” Step by Step</strong></summary>

1. Go to **Run > Run Configurations...** (or **Debug Configurations...** if you're debugging)
2. Select your application under **Java Application**, or create a new one
3. Open the **Arguments** tab
4. In the **VM arguments** field, paste the full `-javaagent:...` argument from above
5. Click **Apply**, then **Run** (or **Debug**)

**Finding the JAR path:** Navigate to the JAR in your file system, right-click it, and copy the full path. Paste it into the agent argument, replacing the placeholder path.

> On Windows, Eclipse accepts both forward and backslashes in paths, but backslashes are safer. Wrap the path in quotes if it contains spaces: `-javaagent:"C:\path with spaces\agent.jar"=...`

</details>

---

## Runtime Configuration

### Java Agent Options

#### Capture History Buffer Size

Controls how many method captures the agent retains per probe point.

| Method               | Value                                            |
| -------------------- | ------------------------------------------------ |
| Agent arg            | `captureMethodBufferSize=<1..32>`                |
| JVM property         | `-Dmcp.probe.capture.method.buffer.size=<1..32>` |
| Environment variable | `MCP_PROBE_CAPTURE_METHOD_BUFFER_SIZE=<1..32>`   |

Default is `3`. Increase this if you need deeper capture history for a single probe point.

### MCP Server Environment Variables

No environment variable is required for the packaged Java server. The adjacent
`sidecar/` directory supplies the lifecycle helper and Sidecar Agent by default.

| Variable                                | Default | Notes |
| --------------------------------------- | ------- | ----- |
| `MCP_WORKSPACE_ROOT`                    | host roots / current directory | Explicit workspace used for `.mcpjvm` state discovery |
| `MCP_JAVA_BIN`                          | `java`  | Java executable used by lifecycle operations |
| `MCP_JAVA_ATTACH_HELPER_JAR`            | packaged helper | Optional lifecycle-helper override |
| `MCP_JAVA_AGENT_JAR`                    | packaged agent | Optional Sidecar Agent override |
| `MCP_JVM_LIFECYCLE_ALLOWED_PROBE_HOSTS` | loopback only | Comma-separated additional Probe hosts allowed for dynamic attach |
| `MCP_SYNTHESIZER_PLUGIN_MODULES`        | none | External Route Synthesis modules, when explicitly configured |
| `MCP_JAVA_DEV_TOOLS_JMETER_HOME`        | `PATH` lookup | Optional Apache JMeter home for performance plans |

### Configuration Scope Matrix

| Setting                                                                                  | Consumed By | Affects                                                                 |
| ---------------------------------------------------------------------------------------- | ----------- | ----------------------------------------------------------------------- |
| `.mcpjvm/probe-config.json`                                                              | MCP server  | Canonical multi-probe routing with workspaces/profiles/probes           |
| `include` / `exclude` in `-javaagent:...` (or `mcp.probe.include` / `MCP_PROBE_INCLUDE`) | Java agent  | Which classes are instrumented at runtime                               |
| Java MCP server configuration                                                           | MCP client  | Starts the packaged JAR over STDIO with exactly three public CDE tools   |

#### Probe Endpoints

These paths are fixed and cannot be overridden.

| Endpoint | Path               |
| -------- | ------------------ |
| Status   | `/__probe/status`  |
| Reset    | `/__probe/reset`   |
| Capture  | `/__probe/capture` |

---

## Skills

| Skill                                        | Purpose                                                                                                      |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `mcp-java-dev-tools-line-probe-run`          | Line-level probe execution                                                                                   |
| `mcp-java-dev-tools-regression-suite`        | Regression check orchestration                                                                               |
| `mcp-java-dev-tools-regression-plan-crafter` | Craft and refine deterministic persisted regression plan specs (`metadata.json`, `contract.json`, `plan.md`) |
| `mcp-java-dev-tools-regression-result`       | Artifact-derived result rendering with extensible display templates (default endpoint table)                 |
| `mcp-java-dev-tools-issue-report`            | Sanitized issue reporting from session, runtime, and probe evidence                                          |
| `mcp-java-dev-tools-bug-drill`               | Bounded method-level diagnosis using Route Synthesis and live Probe evidence                                 |
| `mcp-java-dev-tools-bug-fix`                 | Proposal-only issue localization and Probe-evidenced Java fix planning                                       |
| `mcp-java-dev-tools-failure-lens`            | Bounded Sidecar-backed pasted-exception reproduction with no static-only diagnosis                           |
| `mcp-java-dev-tools-jvm-lifecycle`           | Safe local Java 21+ Sidecar Agent discovery, attachment, Probe verification, and deactivation                |

---

## Contributing

Contribution guidance lives in [CONTRIBUTING.md](./CONTRIBUTING.md).

The guide distinguishes between:

- synthesizer and adapter contributions
- probe tools and recipe generation contributions

Start there before opening a large pull request or changing public tool contracts.

## MCP Tools

The Java STDIO server exposes exactly three public tools. All product
capabilities are operations behind this single Catalog-Describe-Execute surface.

| Tool                 | Purpose |
| -------------------- | ------- |
| `operation_catalog`  | Discover canonical operation IDs and classifications |
| `operation_describe` | Read the Java-owned schema, documentation, and safety policy for one operation |
| `operation_execute`  | Validate and execute one canonical operation through its substantive owner |

Probe config Artifact runtime behavior:

- Registry config is loaded from discovered workspace `.mcpjvm/probe-config.json`.
- File edits are auto-reloaded with debounce.
- `operation_execute` with `operationId=artifact_management.probe_config.reload`
  remains available as the deterministic manual refresh/fallback.
