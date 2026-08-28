package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.util.Map;

/** Renders one bounded generated HTTP workload as a JMeter JMX document. */
public final class JmeterJmxRenderer {

    /** Renders JMeter XML with escaped operator-supplied values. */
    public String render(JmeterWorkloadRequest request) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\"><hashTree>\n"
                + "<TestPlan testname=\"" + escape(request.planName()) + "\" enabled=\"true\"/><hashTree>\n"
                + "<ThreadGroup testname=\"Performance Threads\" enabled=\"true\">\n"
                + "<stringProp name=\"ThreadGroup.num_threads\">" + request.concurrency() + "</stringProp>\n"
                + "<stringProp name=\"ThreadGroup.ramp_time\">" + request.rampUpSeconds() + "</stringProp>\n"
                + "<boolProp name=\"ThreadGroup.scheduler\">true</boolProp>\n"
                + "<stringProp name=\"ThreadGroup.duration\">" + request.durationSeconds() + "</stringProp></ThreadGroup><hashTree>\n"
                + headers(request.headers())
                + "<HTTPSamplerProxy testname=\"" + escape(request.method()) + " " + escape(request.url()) + "\" enabled=\"true\">\n"
                + "<stringProp name=\"HTTPSampler.path\">" + escape(request.url()) + "</stringProp>\n"
                + "<stringProp name=\"HTTPSampler.method\">" + escape(request.method()) + "</stringProp>\n"
                + "<stringProp name=\"HTTPSampler.connect_timeout\">" + request.timeoutMs() + "</stringProp>\n"
                + "<stringProp name=\"HTTPSampler.response_timeout\">" + request.timeoutMs() + "</stringProp>\n"
                + body(request.body()) + "</HTTPSamplerProxy><hashTree/></hashTree></hashTree></hashTree></hashTree></jmeterTestPlan>\n";
    }

    private String headers(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder xml = new StringBuilder(
                "<HeaderManager testname=\"HTTP Header Manager\" enabled=\"true\">"
                        + "<collectionProp name=\"HeaderManager.headers\">");
        headers.forEach((name, value) -> xml.append("<elementProp name=\"").append(escape(name))
                .append("\"><stringProp name=\"Header.name\">").append(escape(name))
                .append("</stringProp><stringProp name=\"Header.value\">").append(escape(value))
                .append("</stringProp></elementProp>"));
        return xml.append("</collectionProp></HeaderManager><hashTree/>\n").toString();
    }

    private static String body(String body) {
        return body == null || body.isBlank()
                ? ""
                : "<boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>\n"
                        + "<elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\">\n"
                        + "<collectionProp name=\"Arguments.arguments\"><elementProp name=\"\" elementType=\"HTTPArgument\">\n"
                        + "<boolProp name=\"HTTPArgument.always_encode\">false</boolProp>\n"
                        + "<stringProp name=\"Argument.value\">" + escape(body) + "</stringProp>\n"
                        + "<stringProp name=\"Argument.metadata\">=</stringProp></elementProp></collectionProp>\n"
                        + "</elementProp>\n";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
