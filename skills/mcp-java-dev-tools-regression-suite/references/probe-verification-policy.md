# Probe Verification Policy

1. If `probeVerification=true`, strict probe gate is mandatory.
2. Strict gate requires:
   - probe listener reachable
   - `probe` with `action=check` succeeds
3. If strict gate fails, block before endpoint execution.
4. Endpoint results cannot be labeled `verified_line_hit` unless line verification evidence is present.
5. In strict mode, no silent downgrade to HTTP-only pass.

