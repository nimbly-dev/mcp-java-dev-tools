package com.nimbly.mcpjavadevtools.requestmapping.api;

import java.util.Optional;

public interface MappingExtractor {
    String strategyId();

    Optional<ResolvedMapping> resolve(MethodContext context, TypeIndex index);
}


