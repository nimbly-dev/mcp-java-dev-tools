# PrePlan token refresh

Configure a project-owned `prePlan` script when a token must be refreshed before a plan. The script may update the project-local environment source used by the declared prerequisite, but it must never print or persist the token value.

```text
script: .mcpjvm/example-project/scripts/refresh-token.<ext>
phase: prePlan
input: project-owned environment source
output: no token value; exit status only
```

Use a placeholder command in documentation, for example `<script-command>`, and provide the real command only in a local, ignored project configuration. On every resume, the declared project-owned source may be re-resolved. The caller must not pass `auth.bearer` to resume, and the resolved value must remain redacted from `execution.result.json`, `evidence.json`, SQLite summaries, MCP output, and logs.
