package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle;

/** Default Core-safe lifecycle for embeddings that do not compose local runtime ownership. */
public final class NoopExecutionRuntimeLifecycle implements ExecutionRuntimeLifecycle {

    @Override
    public RuntimeLifecycleResult prepare(RuntimeLifecycleRequest request) {
        return RuntimeLifecycleResult.notRequired();
    }

    @Override
    public RuntimeLifecycleResult cleanup(RuntimeLifecycleRequest request) {
        return RuntimeLifecycleResult.notRequired();
    }
}
