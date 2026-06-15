# Performance Execution Contract

## Required Contract Facts

1. `suiteType=performance`
2. at least one entrypoint
3. at least one required `Strict Line Key`
4. `loadModel.mode=concurrency`
5. deterministic success criteria

## Required Runtime Rules

1. `Strict Line Key` proof is mandatory.
2. Empty `requiredLineHits` is invalid.
3. Unsupported load models must fail closed.
