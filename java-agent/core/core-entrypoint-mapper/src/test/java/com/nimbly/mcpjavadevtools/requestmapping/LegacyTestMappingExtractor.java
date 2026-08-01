package com.nimbly.mcpjavadevtools.requestmapping;

import com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.core.TypeIndex;
import com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor;
import com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping;

import java.util.Optional;

public final class LegacyTestMappingExtractor implements MappingExtractor {
  @Override
  public String strategyId() {
    return "legacy-test";
  }

  @Override
  public Optional<ResolvedMapping> resolve(MethodContext context, TypeIndex index) {
    ResolvedMapping mapping = new ResolvedMapping();
    mapping.setFramework("legacy");
    mapping.setHttpMethod("GET");
    mapping.setMaterializedPath("/legacy");
    return Optional.of(mapping);
  }
}
