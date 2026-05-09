# mcp-java-dev-tools

[![node](https://img.shields.io/badge/node-v24.13.0-339933?logo=node.js&logoColor=white)](https://nodejs.org/)
[![npm](https://img.shields.io/badge/npm-11.6.2-CB3837?logo=npm&logoColor=white)](https://www.npmjs.com/)
[![JDK](https://img.shields.io/badge/JDK-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Java Agent Target](https://img.shields.io/badge/Java%20Agent%20Target-17-ED8B00?logo=openjdk&logoColor=white)](https://maven.apache.org/)
[![package](https://img.shields.io/badge/package-mcp--java--dev--tools%400.1.5-0A66C2)](https://github.com/nimbly-dev/mcp-java-dev-tools)
[![MCP Badge](https://lobehub.com/badge/mcp/nimbly-dev-mcp-java-dev-tools?style=flat)](https://lobehub.com/mcp/nimbly-dev-mcp-java-dev-tools)

**MCP Java Dev Tools** bridges agentic coding tools and live Java runtime behavior through a lightweight sidecar agent.

Static analysis only gets you so far. By attaching directly to a running JVM, this tool surfaces bytecode-level runtime signals that static analysis alone can't see — enabling probe-verified inspection, targeted regression checks, runtime-path validation, and deterministic debugging workflows.

The runtime agent is built with ByteBuddy and works alongside JDWP rather than replacing it. On top of the probe layer, the system adds framework-aware data synthesis and strict, fail-closed tool contracts — so agent orchestrators can make decisions grounded in actual runtime proof, not inference.

The current focus is HTTP entrypoints. Non-HTTP protocol support is on the horizon but not yet implemented — it will need concrete models and validation targets before the core contracts can be generalized.

For operator workflows and end-to-end execution flows, see [docs/how-it-works/README.md](./docs/how-it-works/README.md).

---

## Requirements

| Requirement | Version |
|---|---|
| Node.js | `v24.13.0` (tested) |
| npm | `11.6.2` (tested) |
| JDK | `17+` |
| Maven | any recent |

---

## Build

```powershell
npm.cmd install
npm.cmd run build
mvn -f java-agent\pom.xml package
```

This produces two artifacts:
- **MCP server** → `dist/server.js`
- **Java agent bundle** → `java-agent/core/core-probe/target/mcp-java-dev-tools-agent-0.1.0-all.jar`

---

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
- `mcp-java-dev-tools-probe-registry-manager`

To update/overwrite existing installed skills (and add missing new skills):

```bash
./scripts/update.sh
```

Both scripts:
- run `npm run build:compile`
- run `mvn -f java-agent/pom.xml package`
- sync shipped skills into the target client skill directory
- by default prompt for a first workspace and generate MCP env config block output (Codex/Kiro specific)

Default MCP registry env input can be skipped:

```bash
./scripts/install.sh --client codex --no-configure-mcp-env
```

MCP env input captures:
- `MCP_JAVA_AGENT_JAR` (required; absolute path to built Java agent jar)

### Spring Integration Launcher

Use the helper launcher to run a Spring app with auto-inferred Java agent include scope and probe port:

```bash
./spring-integration/run-spring-app-with-mcp.sh
```

Behavior:
- prompts for Spring project absolute path, app port (default `8080`), optional JDWP port, and Java 21 compatibility
- infers include package from `src/main/java`
- assigns probe port starting at `9173` and increments if occupied
- opens a new Git Bash window and starts the Spring app with `JAVA_TOOL_OPTIONS` including `-javaagent`

### Manual Setup

#### Java Agent Setup

The target JVM must run on **Java 17 or newer**. If you're on Java 21, see [Java 21 compatibility mode](#java-21-compatibility-mode) before continuing.

Add the following as a JVM argument when launching your application, replacing `{desktopName}`:

```text
-javaagent:C:\Users\{desktopName}\repository\mcp-java-dev-tools\java-agent\core\core-probe\target\mcp-java-dev-tools-agent-0.1.5.jar=host=0.0.0.0;port=9191;exclude=com.nimbly.mcpjavadevtools.agent.**,**.config.**,**Test
```

> **Tip:** The `include` filter is optional. If omitted, the agent infers an include scope from startup command metadata (`sun.java.command`), usually the startup class package (for example `com.acme.app.**`). Set `include` explicitly when inference is ambiguous or too broad.
>
> `include` supports comma-separated basepaths:
> - package globs (for example `com.thirdparty.service.**`)
> - exact class FQCNs (for example `com.example.ApiClass`)
> - mixed module/class targeting in one value (for example `com.example.app.**,com.example.api.**,com.thirdparty.SomeClass`)

To confirm the agent is instrumenting your classes, check the startup logs for lines like:

```txt
[mcp-probe]: com.yourpackagename.yourclassname
```

If you don't see your classes listed, check your `include` filter.

---

<details>
<summary><strong>IntelliJ IDEA — Step by Step</strong></summary>

1. Open **Run > Edit Configurations...** from the top menu
2. Select the run configuration for your target application (or create one if it doesn't exist)
3. Expand the **Modify options** dropdown and enable **Add VM options** if it isn't already visible
4. In the **VM options** field, paste the full `-javaagent:...` argument from above
5. Click **Apply**, then **OK**
6. Run your application normally — the agent attaches on startup

**Finding the JAR path:** If you're unsure of the absolute path, right-click the agent JAR in the Project panel and choose **Copy Path > Absolute Path**.

> On Windows, use backslashes in the path (`C:\Users\...`). On macOS/Linux, use forward slashes (`/home/...` or `/Users/...`).

</details>

---

<details>
<summary><strong>Eclipse — Step by Step</strong></summary>

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

| Method | Value |
|---|---|
| Agent arg | `captureMethodBufferSize=<1..32>` |
| JVM property | `-Dmcp.probe.capture.method.buffer.size=<1..32>` |
| Environment variable | `MCP_PROBE_CAPTURE_METHOD_BUFFER_SIZE=<1..32>` |

Default is `3`. Increase this if you need deeper capture history for a single probe point.

#### Java 21 Compatibility Mode

Required if your target JVM runs on Java 21. Enables ByteBuddy's experimental support for newer JVM internals.

| Method | Value |
|---|---|
| Agent arg | `allowJava21=true` (aliases: `java21Compat=true`, `byteBuddyExperimental=true`) |
| JVM property | `-Dmcp.probe.bytebuddy.experimental=true` (legacy alias: `-Dmcp.probe.allow.java21=true`) |
| Environment variable | `MCP_PROBE_BYTEBUDDY_EXPERIMENTAL=true` (legacy alias: `MCP_PROBE_ALLOW_JAVA21=true`) |

Default is `false`.


### MCP Server Environment Variables

#### Required

| Variable | Purpose |
|---|---|
| `MCP_JAVA_AGENT_JAR` | Absolute path to the built Java agent jar used for probe-wired runtime startup |

#### Optional

| Variable | Default | Notes |
|---|---|---|
| `MCP_JAVA_REQUEST_MAPPING_RESOLVER_JAR` | — | |
| `MCP_JAVA_REQUEST_MAPPING_RESOLVER_CLASSPATH` | — | |
| `MCP_JAVA_BIN` | — | |
| `MCP_PROBE_LINE_SELECTION_MAX_SCAN_LINES` | `120` | Range: 10–2000 |
| `MCP_PROBE_WAIT_MAX_RETRIES` | `1` | Max: 10 |
| `MCP_PROBE_WAIT_UNREACHABLE_RETRY_ENABLED` | `false` | |
| `MCP_PROBE_WAIT_UNREACHABLE_MAX_RETRIES` | `3` | Max: 10 |
| `MCP_PROBE_INCLUDE_EXECUTION_PATHS` | `false` | Set `true` to include `executionPaths` arrays in probe payloads |

### Configuration Scope Matrix

| Setting | Consumed By | Affects |
|---|---|---|
| `.mcpjvm/probe-config.json` | MCP server | Canonical multi-probe routing with workspaces/profiles/probes |
| `include` / `exclude` in `-javaagent:...` (or `mcp.probe.include` / `MCP_PROBE_INCLUDE`) | Java agent | Which classes are instrumented at runtime |
| `MCP_PROBE_INCLUDE_EXECUTION_PATHS` | MCP server | Whether `executionPaths` arrays are included in returned probe payloads |

#### Probe Endpoints

These paths are fixed and cannot be overridden.

| Endpoint | Path |
|---|---|
| Status | `/__probe/status` |
| Reset | `/__probe/reset` |
| Capture | `/__probe/capture` |

---

## Skills

| Skill | Purpose |
|---|---|
| `mcp-java-dev-tools-line-probe-run` | Line-level probe execution |
| `mcp-java-dev-tools-regression-suite` | Regression check orchestration |
| `mcp-java-dev-tools-regression-plan-crafter` | Craft and refine deterministic persisted regression plan specs (`metadata.json`, `contract.json`, `plan.md`) |
| `mcp-java-dev-tools-regression-result` | Artifact-derived result rendering with extensible display templates (default endpoint table) |
| `mcp-java-dev-tools-issue-report` | Sanitized issue reporting from session, runtime, and probe evidence |

---

## Contributing

Contribution guidance lives in [CONTRIBUTING.md](./CONTRIBUTING.md).

The guide distinguishes between:
- synthesizer and adapter contributions
- probe tools and recipe generation contributions

Start there before opening a large pull request or changing public tool contracts.

## MCP Tools

| Tool | |
|---|---|
| `debug_check` | |
| `project_context_validate` | |
| `probe_check` | |
| `probe_target_infer` | |
| `probe_recipe_create` | |
| `probe_get_status` | |
| `probe_get_capture` | |
| `probe_reset` | |
| `probe_wait_for_hit` | |
| `probe_enable` | |
| `probe_registry_list` | |
| `probe_registry_reload` | |

Probe registry runtime behavior:
- Registry config is loaded from discovered workspace `.mcpjvm/probe-config.json`.
- File edits are auto-reloaded with debounce.
- `probe_registry_reload` remains available as deterministic manual refresh/fallback.
