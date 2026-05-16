# Authoring Checklist

1. Confirm `project_name`, `session_id`, and `mode` are present.
2. Confirm session manifest exists and parses as JSON.
3. Confirm mode router selected exactly one branch.
4. Confirm output ordering matches `planRuns[].order`.
5. Confirm redaction/warning policy matches `includeResolvedSecrets`.
6. Confirm fail-closed reason code + next action on any blocked path.
