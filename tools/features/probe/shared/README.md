# Probe Feature-private shared code

Code under this directory is private to the Probe Feature Module. It must not
be imported by another Feature Module or by the Transport Adapter. Promote a
real multi-feature dependency to a named owner under `tools/core`,
`tools/contracts`, or `tools/spec`.
