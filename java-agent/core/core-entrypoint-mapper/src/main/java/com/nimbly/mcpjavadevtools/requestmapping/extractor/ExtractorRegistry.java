package com.nimbly.mcpjavadevtools.requestmapping.extractor;

import com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor;
import com.nimbly.mcpjavadevtools.requestmapping.api.ResolvedMapping;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor;
import com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

public final class ExtractorRegistry {
    private final List<MappingExtractor> extractors;

    public ExtractorRegistry(List<MappingExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public List<MappingExtractor> listExtractors() {
        return extractors;
    }

    public static ExtractorRegistry serviceLoaderDefault() {
        ServiceLoader<MappingExtractor> loader = ServiceLoader.load(MappingExtractor.class);
        List<MappingExtractor> discovered = new ArrayList<>();
        for (MappingExtractor extractor : loader) {
            discovered.add(extractor);
        }
        ServiceLoader<com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor> compatibilityLoader =
                ServiceLoader.load(com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor.class);
        for (com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor extractor : compatibilityLoader) {
            if (discovered.stream().noneMatch(existing -> existing.getClass().equals(extractor.getClass()))) {
                discovered.add(new LegacyMappingExtractorAdapter(extractor));
            }
        }
        discovered.sort(Comparator.comparing(
                MappingExtractor::strategyId));
        return new ExtractorRegistry(discovered);
    }

    private static final class LegacyMappingExtractorAdapter
            implements MappingExtractor {
        private final com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor delegate;

        private LegacyMappingExtractorAdapter(
                com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor delegate) {
            this.delegate = delegate;
        }

        @Override
        public String strategyId() {
            return delegate.strategyId();
        }

        @Override
        public Optional<ResolvedMapping> resolve(MethodContext context, TypeIndex index) {
            Optional<com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping> mapping = delegate.resolve(
                    new com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext(
                            toLegacyDescriptor(context.owner()), context.method(), toLegacyDescriptor(context.originOwner())),
                    new com.nimbly.mcpjavadevtools.requestmapping.core.TypeIndex(
                            index.descriptors().stream().map(LegacyMappingExtractorAdapter::toLegacyDescriptor).toList()));
            return mapping == null ? Optional.empty() : mapping.map(LegacyMappingExtractorAdapter::toApiMapping);
        }

        private static com.nimbly.mcpjavadevtools.requestmapping.ast.TypeDescriptor toLegacyDescriptor(
                TypeDescriptor descriptor) {
            return new com.nimbly.mcpjavadevtools.requestmapping.ast.TypeDescriptor(
                    descriptor.getFileAbs(), descriptor.getTypeDeclaration(), descriptor.getPackageName(),
                    descriptor.getSimpleName(), descriptor.getFqcn(), descriptor.getImports());
        }

        private static ResolvedMapping toApiMapping(
                com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping mapping) {
            ResolvedMapping apiMapping = new ResolvedMapping();
            apiMapping.setFramework(mapping.getFramework());
            apiMapping.setRequestSource(mapping.getRequestSource());
            apiMapping.setHttpMethod(mapping.getHttpMethod());
            apiMapping.setMaterializedPath(mapping.getMaterializedPath());
            apiMapping.setQueryTemplate(mapping.getQueryTemplate());
            apiMapping.setBodyTemplate(mapping.getBodyTemplate());
            apiMapping.setMappingOwnerFile(mapping.getMappingOwnerFile());
            apiMapping.setPathParameters(mapping.getPathParameters());
            apiMapping.setExtensions(mapping.getExtensions());
            return apiMapping;
        }
    }

}


