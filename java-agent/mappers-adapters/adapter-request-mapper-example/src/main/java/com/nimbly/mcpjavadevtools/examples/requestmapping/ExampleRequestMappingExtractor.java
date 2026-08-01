package com.nimbly.mcpjavadevtools.examples.requestmapping;

import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex;
import com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolvedMapping;
import com.nimbly.mcpjavadevtools.requestmapping.api.PathMaterializer;

import java.util.Optional;

/**
 * Generic, practical starter extractor for framework adoptors.
 *
 * <p>Supported pattern:
 * class-level route + method-level verb/route annotations.
 * This is intentionally small but complete enough to copy for new frameworks.</p>
 */
public final class ExampleRequestMappingExtractor implements MappingExtractor {
    private static final String FRAMEWORK_ID = "example_router";
    private final PathMaterializer materializer = new PathMaterializer();

    @Override
    public String strategyId() {
        return "java_ast_example_annotation_router";
    }

    @Override
    public Optional<ResolvedMapping> resolve(MethodContext context, TypeIndex index) {
        Optional<ExampleMappingMerger.ExampleMethodMapping> methodMapping =
                ExampleMappingMerger.resolveMethodMapping(context, index);
        if (methodMapping.isEmpty()) {
            return Optional.empty();
        }

        ExampleMappingMerger.ExampleMethodMapping mapping = methodMapping.get();
        String classPath = ExampleMappingMerger.resolveClassPath(context, index);
        return Optional.of(materializer.materialize(
                FRAMEWORK_ID,
                mapping.httpMethod(),
                classPath,
                mapping.methodPath(),
                context
        ));
    }
}
