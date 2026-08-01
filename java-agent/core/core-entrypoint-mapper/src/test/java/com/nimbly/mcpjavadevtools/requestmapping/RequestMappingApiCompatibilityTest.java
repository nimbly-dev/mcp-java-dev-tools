package com.nimbly.mcpjavadevtools.requestmapping;

import com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor;
import com.github.javaparser.StaticJavaParser;
import com.nimbly.mcpjavadevtools.requestmapping.ast.MethodContext;
import com.nimbly.mcpjavadevtools.requestmapping.core.TypeIndex;
import com.nimbly.mcpjavadevtools.requestmapping.extractor.ExtractorRegistry;
import com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RequestMappingApiCompatibilityTest {
  @Test
  void legacySpiRetainsItsOriginalBinaryMethodDescriptor() throws Exception {
    Method resolve = com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor.class
        .getMethod("resolve", MethodContext.class, TypeIndex.class);

    assertEquals(Optional.class, resolve.getReturnType());
    assertEquals(ResolvedMapping.class, ((java.lang.reflect.ParameterizedType) resolve
        .getGenericReturnType()).getActualTypeArguments()[0]);
    assertNotNull(Class.forName("com.nimbly.mcpjavadevtools.requestmapping.ast.TypeDescriptor"));
    assertNotNull(Class.forName("com.nimbly.mcpjavadevtools.requestmapping.resolution.ResolvedMapping"));
  }

  @Test
  void legacySpiIsAdaptedAtTheRegistryBoundaryInsteadOfBeingTheCanonicalApi() {
    assertFalse(MappingExtractor.class.isAssignableFrom(
        com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor.class));
    assertNotNull(ExtractorRegistry.class);
  }

  @Test
  void legacyProviderIsDiscoverableThroughTheCanonicalRegistry() {
    var type = StaticJavaParser.parse("class Owner { void endpoint() {} }").getType(0);
    var descriptor = new com.nimbly.mcpjavadevtools.requestmapping.api.TypeDescriptor(
        java.nio.file.Path.of("Owner.java"), type, "", "Owner", "Owner", List.of());
    var context = new com.nimbly.mcpjavadevtools.requestmapping.api.MethodContext(
        descriptor, type.getMethods().get(0), descriptor);
    var index = new com.nimbly.mcpjavadevtools.requestmapping.api.TypeIndex(
        Map.of(), Map.of(), 0);

    var legacy = ExtractorRegistry.serviceLoaderDefault().listExtractors().stream()
        .filter(extractor -> extractor.strategyId().equals("legacy-test"))
        .findFirst()
        .orElseThrow();
    var mapping = legacy.resolve(context, index).orElseThrow();

    assertEquals("legacy", mapping.getFramework());
    assertEquals("/legacy", mapping.getMaterializedPath());
  }
}
