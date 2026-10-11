package com.nimbly.mcpjavadevtools.server;

/** Shared immutable text used by packaged Java MCP acceptance tests. */
final class McpServerAcceptanceConstants {

    static final String PROTOCOL_VERSION = "2025-03-26";
    static final String LOOPBACK_HTTP_PREFIX = "http://127.0.0.1:";
    static final String EVENT_PRODUCER_PROBE_ID = "event-producer-app";
    static final String EVENT_CONSUMER_PROBE_ID = "event-consumer-app";
    static final String EVENT_PRODUCER_INCLUDE = "com.example.social.event.producer.**";
    static final String EVENT_CONSUMER_INCLUDE = "com.example.social.event.consumer.**";
    static final String USER_CONTROLLER = "com.example.social.user.app.controller.UserController";
    static final String USER_PROFILE_LINE_KEY = USER_CONTROLLER + "#getUserProfile:20";
    static final String USER_FAILURE_TRACE = "java.lang.IllegalStateException: fixture failure\n"
            + "\tat " + USER_CONTROLLER + ".getUserProfile(UserController.java:20)";
    static final String EVENT_CONSUMER_LISTENER =
            "com.example.social.event.consumer.app.listener.ExampleQueueListener";
    static final String EVENT_CONSUMER_PAYLOAD =
            "com.example.social.event.consumer.app.model.IndexRequestedEvent";
    static final String REGRESSION_PLAN_TEXT = "# regression plan\n";
    static final String SQL_NULL_AND_QUOTED_PARAMETER_QUERY =
            "SELECT status, :nullable AS nullable, ':literal' AS literal FROM fixture_health "
                    + "/* :comment */ WHERE id = :id -- :tail";
    static final String SECURITY_PLAN_TEXT = "# Security acceptance plan\n";
    static final String PERFORMANCE_PLAN_TEXT = "# Performance acceptance plan\n";
    static final String PROBE_REGRESSION_PLAN_TEXT = "# Regression Probe acceptance plan\n";
    static final String CORRELATION_PLAN_TEXT =
            "# Java packaged cross-service event correlation acceptance\n";
    static final String CROSS_PLAN_CONSUMER_TEXT =
            "# Java packaged cross-plan consumer acceptance\n";

    private McpServerAcceptanceConstants() {
    }
}
