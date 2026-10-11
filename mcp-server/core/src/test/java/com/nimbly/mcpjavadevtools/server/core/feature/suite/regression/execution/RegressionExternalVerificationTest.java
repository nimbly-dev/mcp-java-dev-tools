package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RegressionExternalVerificationTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegressionExternalVerification verification = new RegressionExternalVerification(
            new RegressionVerificationSupport(request -> {
                throw new AssertionError("SQL verification must use JDBC");
            }, mapper));

    @ParameterizedTest
    @MethodSource("quotedSql")
    void executesSqlWithoutBindingNamesInsideLiteralsIdentifiersOrComments(String sql) {
        ObjectNode input = input(sql);

        RegressionExternalVerification.Result result = verification.execute(input, context());

        assertThat(result.success()).as(result.toString()).isTrue();
        assertThat(mapper.valueToTree(result.details()).get(0).at("/sql/firstRow/value").asText())
                .isEqualTo(":literal");
    }

    static Stream<String> quotedSql() {
        return Stream.of(
                "SELECT ':literal' AS value",
                "SELECT ':lit''eral' AS ignored, ':literal' AS value",
                "SELECT ':literal' AS value, 1 AS \"colon:identifier\"",
                "SELECT ':literal' AS value, 1 AS \"colon\"\":identifier\"",
                "SELECT ':literal' AS value, 1 AS `colon:identifier`",
                "SELECT ':literal' AS value, 1 AS [colon:identifier]",
                "SELECT ':literal' AS value -- :comment",
                "SELECT ':literal' AS value -- :comment\n",
                "SELECT /* :comment */ ':literal' AS value",
                "SELECT ':literal' AS value /* ':comment' */");
    }

    @Test
    void skipsLongQuotedLiteralsWithoutRecursiveRegexBacktracking() {
        String literal = ":literal".repeat(2_000);
        ObjectNode input = input("SELECT '" + literal + "' AS value, :bound AS bound");
        parameters(input).put("name", "bound").put("value", 7);

        RegressionExternalVerification.Result result = verification.execute(input, context());

        assertThat(result.success()).as(result.toString()).isTrue();
        JsonNode row = mapper.valueToTree(result.details()).get(0).at("/sql/firstRow");
        assertThat(row.path("value").asText()).isEqualTo(literal);
        assertThat(row.path("bound").asInt()).isEqualTo(7);
    }

    @Test
    void bindsLiteralNullAndPreservesSqlNullInTheResult() {
        ObjectNode input = input("SELECT :value AS value");
        parameters(input).put("name", "value").putNull("value");

        assertNullRow(verification.execute(input, context()));
    }

    @Test
    void bindsContextNullAndRepeatedNamesInOccurrenceOrder() {
        ObjectNode input = input("SELECT :value AS value, :other AS other, :value AS repeated");
        parameters(input).put("name", "value").put("valueFromContext", "nullableValue");
        parameters(input).put("name", "other").put("value", 7);
        Map<String, Object> context = context();
        context.put("nullableValue", null);

        RegressionExternalVerification.Result result = verification.execute(input, context);

        assertNullRow(result);
        JsonNode row = mapper.valueToTree(result.details()).get(0).at("/sql/firstRow");
        assertThat(row.path("other").asInt()).isEqualTo(7);
        assertThat(row.path("repeated").isNull()).isTrue();
    }

    @Test
    void bindsOnlyExecutableParametersAndKeepsValuesOutOfSqlText() {
        ObjectNode input = input("SELECT :value AS value, :value AS repeated "
                + "/* :missing */ -- :also_missing\n");
        String value = "':unbound'; DROP TABLE verification; --";
        parameters(input).put("name", "value").put("value", value);

        RegressionExternalVerification.Result result = verification.execute(input, context());

        assertThat(result.success()).as(result.toString()).isTrue();
        JsonNode row = mapper.valueToTree(result.details()).get(0).at("/sql/firstRow");
        assertThat(row.path("value").asText()).isEqualTo(value);
        assertThat(row.path("repeated").asText()).isEqualTo(value);
    }

    @Test
    void stillFailsClosedForAnUnresolvedExecutableParameter() {
        RegressionExternalVerification.Result result = verification.execute(
                input("SELECT ':literal' AS ignored, :missing AS value"), context());

        assertThat(result.success()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("external_verification_request_unresolved");
    }

    private void assertNullRow(RegressionExternalVerification.Result result) {
        assertThat(result.success()).as(result.toString()).isTrue();
        JsonNode sql = mapper.valueToTree(result.details()).get(0).path("sql");
        assertThat(sql.path("rowCount").asInt()).isEqualTo(1);
        assertThat(sql.path("firstRow").has("value")).isTrue();
        assertThat(sql.at("/firstRow/value").isNull()).isTrue();
    }

    private Map<String, Object> context() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sql.connection.database.kind", "sqlite");
        context.put("sql.connection.database.sqlite.filePath",
                temporaryDirectory.resolve("verification.sqlite").toString());
        return context;
    }

    private ObjectNode input(String statement) {
        ObjectNode input = mapper.createObjectNode();
        ObjectNode external = input.putObject("contract").putArray("externalVerification")
                .addObject().put("id", "database");
        external.putObject("provider").put("type", "sql");
        external.putObject("request").putObject("sql").put("connectionRef", "database")
                .put("statement", statement).putArray("parameters");
        return input;
    }

    private ObjectNode parameters(ObjectNode input) {
        return ((ObjectNode) input.at("/contract/externalVerification/0/request/sql"))
                .withArray("parameters").addObject();
    }
}
