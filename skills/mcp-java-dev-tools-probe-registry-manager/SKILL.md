---
name: mcp-java-dev-tools-probe-registry-manager
description: "Manage probe registry configuration (`probe-config.json`): add/update/list probes, validate config, reload registry, and generate MCP run snippets."
---

# MCP Java Dev Tools Probe Registry Manager

Use this skill for operational management of `.mcpjvm/probe-config.json` and registry runtime refresh.

## Scope

1. Add/update/remove probe entries in `.mcpjvm/probe-config.json`.
2. Validate deterministic registry shape (`defaultProfile`, `workspaces[]`, `profiles`, `probes`).
3. Trigger `probe_registry_reload` after changes.
4. Provide ready-to-paste MCP client configuration snippets.
5. Use `artifact_management` with `artifactType=probe_config` for read/validate/upsert artifact operations.

## Rules

1. Preserve deterministic routing: `probeId` must be stable and unique per profile.
2. Do not infer service ports or probe routes from guesses; require explicit values.
3. Keep selectors explicit:
   - `include[]`
   - `exclude[]`
4. Keep per-probe `runtime` generic metadata only (for example `platform`, `port`).
5. If required fields are missing, fail closed and report the exact missing fields.
6. `workspaces[]` entries MUST use `root` (not `workspaceRoot`).
7. Probe `baseUrl` MUST point to probe endpoint mapping (java-agent probe port), not application API `server.port`.

## Config Shape (Required)

```json
{
  "defaultProfile": "dev",
  "profiles": {
    "dev": {
      "defaultProbe": "gateway-service",
      "probes": {}
    }
  },
  "workspaces": [
    {
      "root": "C:\\\\workspace\\\\example",
      "profile": "dev"
    }
  ]
}
```

## Minimal Probe Entry Contract

```json
{
  "baseUrl": "http://127.0.0.1:9190",
  "include": ["com.example.orders.**"],
  "exclude": [],
  "runtime": {
    "platform": "spring-boot",
    "port": 8080
  }
}
```

Notes:
1. `baseUrl` is probe endpoint URL (for example `http://127.0.0.1:9193`), not application API URL.
2. `runtime.port` is application API/server port (for example `9001`) when known.

## Workflow

1. Call `artifact_management` with `artifactType=probe_config` and `action=read`.
2. Validate registry contract through `artifact_management` `action=validate`.
3. Apply requested mutation (add/update/remove workspace/profile/probe).
4. Persist via `artifact_management` with `artifactType=probe_config` and `action=upsert`.
5. Re-validate with `artifact_management` `action=validate`.
6. Call `probe_registry_reload`.
7. Return:
   - change summary
   - any Fail-Closed validation errors
   - MCP run snippet

## Probe Port Resolution Policy (Strict)

When asked to auto-populate probes:

1. Resolve service runtime app ports from proven sources (for example `application.yml`, compose service ports).
2. Resolve probe endpoint ports from proven probe mappings only (for example docker compose `hostProbePort:9191` or explicit javaagent runtime mapping).
3. If probe endpoint mapping is missing for any service, do not invent `baseUrl`; return blocked guidance listing missing probe mappings.
4. Never set `baseUrl` to app API ports (`5000`, `9000`, etc.) unless evidence proves probe is exposed on that same port.

## Deployment Mode Policy (Strict)

When setting `runtime.port`:

1. `runtime.port` represents the application API port used by caller-side execution.
2. In Docker Compose host-mode mappings (`host:container`), prefer host-published app port for `runtime.port` (for example `9001:8080` -> `runtime.port=9001`).
3. Do not use container-internal app port (`8080`) as `runtime.port` unless execution is explicitly inside container network.
4. If deployment mode is ambiguous (host vs container-network), fail closed and request explicit mode.

When setting `include[]`:

1. Do not assign one shared wildcard include to all services when service-specific package evidence exists.
2. Prefer service-specific include package roots per probeId (for example course/review/composite/gateway package roots).
3. If package ownership cannot be proven for a service, fail closed for that service entry and report missing ownership evidence.

## MCP Run Snippet Template

```json
{
  "mcpServers": {
    "mcp-java-dev-tools": {
      "command": "node",
      "args": ["C:\\path\\to\\mcp-jvm-debugger\\dist\\server.js"],
      "env": {
        "MCP_JAVA_AGENT_JAR": "C:\\path\\to\\mcp-java-dev-tools-agent-0.1.7-all.jar"
      }
    }
  }
}
```

