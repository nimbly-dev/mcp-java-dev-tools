# Runtime Policy

## `autoStart=true`

1. If runtime is down, start from `projects.json` selected runtime context.
2. If runtime API is up but probe is down, treat as non-compliant and replace/restart via runtime context.
3. Do not use ad-hoc startup commands when runtime context exists.

## `autoStart=false`

1. Never spawn or restart processes.
2. Require runtime already compliant (`API reachable` and `probe reachable` when strict probe is required).
3. If not compliant, fail closed.

## Startup Authority

1. `projects.json` runtime context is authoritative for startup/restart flow.
2. `probe-config.json` is authoritative for probe port/include wiring.

## Runtime Context Selection

1. If `runtimeContextName` is provided, it MUST match an existing runtime context or fail closed.
2. If `runtimeContextName` is omitted:
   - select `terminal-cli` when present
   - otherwise select any terminal context
3. If multiple non-terminal runtime contexts exist and no explicit selection is provided, fail closed and require `runtimeContextName`.
4. Docker startup paths are allowed only when selected context mode is `docker`.

