package com.nimbly.mcpjavadevtools.agent.runtime;

import java.util.List;
import java.util.Objects;

/** Explicit plan-owned selector for a correlation consumer boundary. */
public record CorrelationConsumerBoundary(
    String id,
    String fqcn,
    String method,
    List<String> parameterTypes,
    int eventArgumentIndex
) {
  public CorrelationConsumerBoundary {
    id = requireText(id, "id");
    fqcn = requireText(fqcn, "fqcn");
    method = requireText(method, "method");
    Objects.requireNonNull(parameterTypes, "parameterTypes");
    parameterTypes = List.copyOf(parameterTypes);
    if (parameterTypes.isEmpty()) {
      throw new IllegalArgumentException("parameterTypes must not be empty");
    }
    parameterTypes = parameterTypes.stream()
        .map(parameterType -> requireText(parameterType, "parameterType"))
        .toList();
    if (eventArgumentIndex < 0 || eventArgumentIndex >= parameterTypes.size()) {
      throw new IllegalArgumentException("eventArgumentIndex is outside parameterTypes");
    }
  }

  public boolean matches(java.lang.reflect.Method origin) {
    if (!origin.getDeclaringClass().getName().equals(fqcn)
        || !origin.getName().equals(method)
        || origin.getParameterCount() != parameterTypes.size()) {
      return false;
    }
    Class<?>[] originParameterTypes = origin.getParameterTypes();
    for (int index = 0; index < originParameterTypes.length; index++) {
      if (!originParameterTypes[index].getName().equals(parameterTypes.get(index))) {
        return false;
      }
    }
    return true;
  }

  private static String requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalized;
  }
}
