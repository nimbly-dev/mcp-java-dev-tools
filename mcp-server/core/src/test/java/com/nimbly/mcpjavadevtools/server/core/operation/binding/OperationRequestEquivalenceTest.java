package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class OperationRequestEquivalenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void distinguishesDottedTopLevelNamesFromNestedPointerPaths() throws Exception {
        JsonNode left = JSON.readTree("{\"a\":{}}");
        JsonNode right = JSON.readTree("{\"a\":{\"b\":1}}");
        JsonNode value = JSON.readTree("1");

        assertThat(OperationRequestEquivalence.equivalent(
                left, right, Map.of("a.b", value), OperationSchema.empty())).isFalse();
        assertThat(OperationRequestEquivalence.equivalent(
                left, right, Map.of("/a/b", value), OperationSchema.empty())).isTrue();
    }

    @Test
    void retainsTopLevelDottedFieldCompatibility() throws Exception {
        JsonNode left = JSON.readTree("{}");
        JsonNode right = JSON.readTree("{\"a.b\":1}");

        assertThat(OperationRequestEquivalence.equivalent(
                left, right, Map.of("a.b", JSON.readTree("1")), OperationSchema.empty())).isTrue();
    }
}
