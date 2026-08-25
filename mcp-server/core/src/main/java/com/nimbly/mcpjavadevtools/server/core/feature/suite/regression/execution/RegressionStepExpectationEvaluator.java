package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates status, header, JSON, and body expectations against one internal response. */
final class RegressionStepExpectationEvaluator {

    private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile("([A-Za-z_][A-Za-z0-9_-]*)(.*)");
    private static final Pattern INDEX_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final ObjectMapper mapper;

    RegressionStepExpectationEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String firstFailure(JsonNode expectations, Map<String, Object> response) {
        Map<String, Object> output = Map.of("response", response);
        for (JsonNode expectation : expectations) {
            if (!matches(expectation, output)) {
                return "step_expectation_failed";
            }
        }
        return null;
    }

    private boolean matches(JsonNode expectation, Map<String, Object> response) {
        Object actual = read(response, expectation.path("actualPath").asText());
        String operator = expectation.path("operator").asText();
        Object expected = expectation.has("expected") ? value(expectation.get("expected")) : null;
        if ("field_exists".equals(operator)) {
            return actual != null;
        }
        if ("field_equals".equals(operator)) {
            return Objects.equals(actual, expected);
        }
        if ("field_matches_regex".equals(operator)) {
            return matchesRegex(actual, expected);
        }
        if ("numeric_gte".equals(operator)) {
            return comparesAtLeast(actual, expected);
        }
        if ("numeric_lte".equals(operator)) {
            return comparesAtMost(actual, expected);
        }
        if ("contains".equals(operator)) {
            return contains(actual, expected);
        }
        return false;
    }

    private boolean matchesRegex(Object actual, Object expected) {
        if (!(actual instanceof String text) || !(expected instanceof String regex)) {
            return false;
        }
        try {
            return Pattern.compile(regex).matcher(text).find();
        } catch (PatternSyntaxException exception) {
            return false;
        }
    }

    private boolean comparesAtLeast(Object actual, Object expected) {
        Integer comparison = compare(actual, expected);
        return comparison != null && comparison >= 0;
    }

    private boolean comparesAtMost(Object actual, Object expected) {
        Integer comparison = compare(actual, expected);
        return comparison != null && comparison <= 0;
    }

    private Integer compare(Object left, Object right) {
        if (!(left instanceof Number actual) || !(right instanceof Number expected)) {
            return null;
        }
        return BigDecimal.valueOf(actual.doubleValue()).compareTo(BigDecimal.valueOf(expected.doubleValue()));
    }

    private boolean contains(Object actual, Object expected) {
        if (actual instanceof String text && expected instanceof String item) {
            return text.contains(item);
        }
        return actual instanceof List<?> values && values.contains(expected);
    }

    private Object value(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return mapper.convertValue(node, Object.class);
    }

    static Object read(Map<String, Object> values, String path) {
        Object current = values;
        for (String segment : path.split("\\.")) {
            Matcher segmentMatcher = PATH_SEGMENT_PATTERN.matcher(segment);
            if (!segmentMatcher.matches() || !(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segmentMatcher.group(1));
            if (current == null) {
                return null;
            }
            Matcher indexMatcher = INDEX_PATTERN.matcher(segmentMatcher.group(2));
            while (indexMatcher.find()) {
                if (!(current instanceof List<?> list)) {
                    return null;
                }
                int index = Integer.parseInt(indexMatcher.group(1));
                if (index >= list.size()) {
                    return null;
                }
                current = list.get(index);
                if (current == null) {
                    return null;
                }
            }
        }
        return current;
    }
}
