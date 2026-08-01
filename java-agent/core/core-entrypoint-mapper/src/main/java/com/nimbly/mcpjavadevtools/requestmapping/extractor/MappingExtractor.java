package com.nimbly.mcpjavadevtools.requestmapping.extractor;

import com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.core.TypeIndex;
import com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping;
import java.util.Optional;

@Deprecated(forRemoval = false)
public interface MappingExtractor {
    String strategyId();

    Optional<ResolvedMapping> resolve(MethodContext context, TypeIndex index);
}
