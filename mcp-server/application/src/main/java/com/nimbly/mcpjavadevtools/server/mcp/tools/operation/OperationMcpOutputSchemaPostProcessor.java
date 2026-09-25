package com.nimbly.mcpjavadevtools.server.mcp.tools.operation;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Keeps generated CDE output schemas aligned with nullable and JSON-valued result fields. */
public class OperationMcpOutputSchemaPostProcessor implements BeanPostProcessor {

    private static final String CATALOG_TOOL = "operation_catalog";
    private static final String DESCRIBE_TOOL = "operation_describe";
    private static final String EXECUTE_TOOL = "operation_execute";
    private static final List<String> JSON_VALUE_TYPES =
            List.of("null", "object", "array", "string", "number", "integer", "boolean");

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> specifications)
                || specifications.stream().noneMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return bean;
        }
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.SyncToolSpecification> tools =
                (List<McpServerFeatures.SyncToolSpecification>) specifications;
        if (tools.stream().noneMatch(specification -> isCdeTool(specification.tool().name()))) {
            return bean;
        }
        return tools.stream().map(this::replaceOutputSchema).toList();
    }

    private McpServerFeatures.SyncToolSpecification replaceOutputSchema(
            McpServerFeatures.SyncToolSpecification specification) {
        McpSchema.Tool source = specification.tool();
        String name = source.name();
        if (!isCdeTool(name)) {
            return specification;
        }
        Map<String, Object> schema = mutableMap(source.outputSchema());
        if (CATALOG_TOOL.equals(name)) {
            allowNullableField(schema, "nextCursor", List.of("string", "null"));
        } else if (DESCRIBE_TOOL.equals(name)) {
            allowNullableField(schema, "operation", List.of("object", "null"));
            allowJsonValueNestedField(schema, "operation", "defaultValue");
        } else {
            allowNullableField(schema, "operationId", List.of("string", "null"));
            allowJsonValueField(schema, "result");
        }
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(source.name())
                .title(source.title())
                .description(source.description())
                .inputSchema(source.inputSchema())
                .outputSchema(schema)
                .annotations(source.annotations())
                .icons(source.icons())
                .meta(source.meta())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, specification.callHandler());
    }

    private static boolean isCdeTool(String name) {
        return CATALOG_TOOL.equals(name) || DESCRIBE_TOOL.equals(name) || EXECUTE_TOOL.equals(name);
    }

    private static void allowJsonValueNestedField(
            Map<String, Object> schema, String parentName, String fieldName) {
        Map<String, Object> properties = mapValue(schema.get("properties"));
        Map<String, Object> parentSchema = mapValue(properties.get(parentName));
        if (!findAndSetFieldType(parentSchema, fieldName, JSON_VALUE_TYPES, true)) {
            throw new IllegalStateException("Generated CDE output schema is missing nested field "
                    + parentName + "." + fieldName + ": " + parentSchema);
        }
    }

    private static void allowNullableField(Map<String, Object> schema, String fieldName, List<String> types) {
        Map<String, Object> properties = mapValue(schema.get("properties"));
        setFieldType(fieldSchema(properties, fieldName, false), types, fieldName + " in " + schema, false);
    }

    private static void allowJsonValueField(Map<String, Object> schema, String fieldName) {
        Map<String, Object> properties = mapValue(schema.get("properties"));
        setFieldType(fieldSchema(properties, fieldName, true), JSON_VALUE_TYPES, fieldName + " in " + schema, true);
    }

    private static boolean findAndSetFieldType(
            Map<String, Object> schema, String fieldName, List<String> types, boolean replace) {
        Map<String, Object> properties = mapValue(schema.get("properties"));
        if (properties.containsKey(fieldName)) {
            setFieldType(fieldSchema(properties, fieldName, replace), types, fieldName, replace);
            return true;
        }
        for (Object value : properties.values()) {
            if (findAndSetFieldType(mapValue(value), fieldName, types, replace)) {
                return true;
            }
        }
        Object items = schema.get("items");
        return items instanceof Map<?, ?> && findAndSetFieldType(mapValue(items), fieldName, types, replace);
    }

    private static Map<String, Object> fieldSchema(
            Map<String, Object> properties, String fieldName, boolean replace) {
        Object source = properties.get(fieldName);
        if (source instanceof Map<?, ?>) {
            return mapValue(source);
        }
        if (!replace) {
            throw new IllegalStateException("Generated CDE output schema is missing field " + fieldName + ".");
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        properties.put(fieldName, schema);
        return schema;
    }

    private static void setFieldType(
            Map<String, Object> fieldSchema, List<String> types, String fieldPath, boolean replace) {
        if (fieldSchema.isEmpty() && !replace) {
            throw new IllegalStateException("Generated CDE output schema is missing field " + fieldPath + ".");
        }
        if (replace) {
            fieldSchema.clear();
        }
        fieldSchema.put("type", types);
    }

    private static Map<String, Object> mutableMap(Map<String, Object> source) {
        if (source == null) {
            throw new IllegalStateException("Generated CDE output schema is missing.");
        }
        return objectMap(copyValue(source));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, nested) -> {
            if (key instanceof String name) {
                result.put(name, copyValue(nested));
            }
        });
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            return objectMap(source);
        }
        if (value instanceof List<?> source) {
            List<Object> result = new ArrayList<>(source.size());
            source.forEach(nested -> result.add(copyValue(nested)));
            return result;
        }
        return value;
    }
}
