# Output Contract

## Required Human Run Summary

Always include:

1. `Scope` (`controller` | `service` | `api`)
2. `Routing Outcome`
3. `Endpoint Results` (method/path/http code)
4. `Probe Coverage`
   - `verified_line_hit`
   - `http_only_unverified_line`
   - `unknown`
5. `Probe Verification`
6. `Run Timing`
   - `runStartEpoch`
   - `runEndEpoch`
   - `runDurationMs`
7. `Synthesis Diagnostics`
8. `Runtime Evidence`
9. `Repro Steps`
10. `Cleanup`
11. `Trust Note`

## Repro Steps Format

1. Lead with direct human HTTP actions.
2. Include expected outcomes per step.
3. Keep runnable in curl/Postman/browser.
4. MCP internals belong only in optional `Toolchain Steps`.
