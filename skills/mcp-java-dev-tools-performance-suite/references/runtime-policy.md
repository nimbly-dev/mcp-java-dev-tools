# Performance Runtime Policy

1. Performance execution must use the project/runtime context selected by the matching execution profile.
2. Observed Java targets are identified by `Strict Line Key`, not by inferred component type.
3. Entrypoint transport and observed Java target are separate concerns.
4. Wrapped transport remains the canonical request path when entrypoint execution is HTTP-based.
