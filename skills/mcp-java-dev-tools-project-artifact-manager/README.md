# Project Artifact Manager References

This skill is extensible by design.

Use `references/` to keep external-system discovery rules modular instead of growing `SKILL.md` into one large document.

Current references:

1. `references/postgres.md`
2. `references/dynamodb.md`
3. `references/keycloak.md`
4. `references/validation-rules.md`
5. `templates/projects.terminal.example.json`

Update policy:

1. Add one file per external system family.
2. Keep discovery evidence deterministic (config paths, compose mappings, known endpoints).
3. Do not include secret values; include only env key references.
4. Keep health checks to deterministic `tcp` and `http` patterns unless spec expands.

Validation policy:

1. Validate first, then write.
2. Return compact fail-closed outputs (`status`, `reasonCode`, `checks[]`, `nextAction`) for ambiguous runtime or startup configuration.

