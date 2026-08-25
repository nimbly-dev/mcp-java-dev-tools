package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluates a bounded Regression condition before external work. */
final class RegressionStepConditionEvaluator {

    Evaluation evaluate(JsonNode node, Map<String, Object> context, Map<Integer, Map<String, Object>> outputs, int order) {
        if (node.isMissingNode() || node.isNull()) {
            return Evaluation.match();
        }
        if (node.has("not")) {
            Evaluation child = evaluate(node.path("not"), context, outputs, order);
            return child.blocked() ? child : new Evaluation(!child.matches(), null);
        }
        if (node.has("all") || node.has("any")) {
            return composite(node, context, outputs, order);
        }
        return predicate(node, context, outputs, order);
    }

    private Evaluation composite(JsonNode node, Map<String, Object> context, Map<Integer, Map<String, Object>> outputs, int order) {
        JsonNode values = node.has("all") ? node.path("all") : node.path("any");
        if (!values.isArray() || values.isEmpty()) {
            return Evaluation.blocked("step_condition_malformed");
        }
        boolean all = node.has("all");
        for (JsonNode child : values) {
            Evaluation result = evaluate(child, context, outputs, order);
            if (result.blocked() || (all && !result.matches()) || (!all && result.matches())) {
                return result;
            }
        }
        return new Evaluation(all, null);
    }

    private Evaluation predicate(JsonNode node, Map<String, Object> context, Map<Integer, Map<String, Object>> outputs, int order) {
        Object actual = resolve(node.path("left").asText(), context, outputs, order);
        if (actual == Missing.VALUE) {
            return Evaluation.blocked("step_condition_path_missing");
        }
        String operator = node.path("op").asText();
        if ("exists".equals(operator)) {
            return new Evaluation(actual != null, null);
        }
        if (!node.has("right")) {
            return Evaluation.blocked("step_condition_type_mismatch");
        }
        Object expected = value(node.get("right"));
        if ("equals".equals(operator)) {
            return new Evaluation(Objects.equals(actual, expected), null);
        }
        if ("not_equals".equals(operator)) {
            return new Evaluation(!Objects.equals(actual, expected), null);
        }
        if ("in".equals(operator) && node.path("right").isArray()) {
            for (JsonNode candidate : node.path("right")) {
                if (Objects.equals(actual, value(candidate))) {
                    return Evaluation.match();
                }
            }
            return new Evaluation(false, null);
        }
        return Evaluation.blocked("step_condition_operator_invalid");
    }

    private Object resolve(String left, Map<String, Object> context, Map<Integer, Map<String, Object>> outputs, int order) {
        if (left.startsWith("context.")) {
            return RegressionStepExpectationEvaluator.read(context, left.substring(8));
        }
        Matcher match = Pattern.compile("step\\[(\\d+)]\\.(.+)").matcher(left);
        if (!match.matches()) {
            return Missing.VALUE;
        }
        int referenced = Integer.parseInt(match.group(1));
        if (referenced >= order || !outputs.containsKey(referenced)) {
            return Missing.VALUE;
        }
        return RegressionStepExpectationEvaluator.read(outputs.get(referenced), match.group(2));
    }

    private Object value(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.toString();
    }

    record Evaluation(boolean matches, String reasonCode) {
        static Evaluation match() {
            return new Evaluation(true, null);
        }
        static Evaluation blocked(String reasonCode) {
            return new Evaluation(false, reasonCode);
        }
        boolean blocked() {
            return reasonCode != null;
        }
    }

    private enum Missing { VALUE }
}
