# Performance Output Contract

Terminal suite outputs should summarize:

1. execution status
2. threshold verdicts
3. required line-hit verdict
4. MSTA status when timing analysis is enabled
5. Artifact paths

Do not summarize a run as successful if required line-hit proof is missing.
Do not summarize MSTA as available unless the persisted Artifact status is `available`.
