# Synthesis and Routing

## MCP-First Requirement

1. Mandatory tools:
   - `probe` (`action=check`)
   - `artifact_management`
   - `route_synthesis` (`action=create_recipe`)
2. HTTP transport must use MCP-wrapped `transport_execute`.
3. Never fall back to raw curl/direct HTTP execution.
4. If toolchain is unavailable, stop with:
   - `reasonCode=toolchain_unavailable`
   - `nextAction=enable_mcp_jvm_debugger_tools_then_rerun`

## Wrapped HTTP Payload Contract

1. For JSON `POST`/`PUT`/`PATCH`, `transport_execute.request.body` must be a JSON string.
2. Do not pass object bodies directly to wrapped transport.
3. If body is object-shaped at callsite, serialize deterministically before execution.

## Recipe Synthesis Policy

1. Treat `route_synthesis` with `action=create_recipe` as deterministic and fail-closed.
2. Use `intentMode=regression`.
3. Pass exact FQCN in `classHint`.
4. Runtime synthesis scope excludes test sources.
5. If runtime has context path, pass `apiBasePath` once and reuse it.
6. If `resultType=report`, keep fail-closed diagnostics and continue manual flow only when feasible.

## Route Resolution

1. Validate candidates by probe reachability, API reachability, and strict line-target alignment.
2. Continue only when exactly one candidate is valid.
3. On failure, return deterministic codes such as:
   - `probe_route_not_found`
   - `probe_route_ambiguous`
