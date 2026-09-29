package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.net.URI;
import java.util.Map;

/** Renders one bounded generated HTTP workload as a JMeter JMX document. */
public final class JmeterJmxRenderer {

    /** Renders JMeter XML with escaped operator-supplied values. */
    public String render(JmeterWorkloadRequest request) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n")
                .append("  <hashTree>\n")
                .append(testPlan(request.planName()))
                .append("    <hashTree>\n")
                .append(threadGroup(request))
                .append("      <hashTree>\n")
                .append(headers(request.headers()))
                .append(sampler(request))
                .append("      </hashTree>\n")
                .append("    </hashTree>\n")
                .append("  </hashTree>\n")
                .append("</jmeterTestPlan>\n");
        return xml.toString();
    }

    private static String testPlan(String planName) {
        return "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\""
                + escape(planName) + "\" enabled=\"true\">\n"
                + "      <stringProp name=\"TestPlan.comments\"></stringProp>\n"
                + "      <boolProp name=\"TestPlan.functional_mode\">false</boolProp>\n"
                + "      <boolProp name=\"TestPlan.tearDown_on_shutdown\">true</boolProp>\n"
                + "      <boolProp name=\"TestPlan.serialize_threadgroups\">false</boolProp>\n"
                + "      <elementProp name=\"TestPlan.user_defined_variables\" elementType=\"Arguments\">\n"
                + "        <collectionProp name=\"Arguments.arguments\"/>\n"
                + "      </elementProp>\n"
                + "      <stringProp name=\"TestPlan.user_define_classpath\"></stringProp>\n"
                + "    </TestPlan>\n";
    }

    private static String threadGroup(JmeterWorkloadRequest request) {
        return "      <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" "
                + "testname=\"Performance Threads\" enabled=\"true\">\n"
                + "        <stringProp name=\"ThreadGroup.on_sample_error\">continue</stringProp>\n"
                + "        <elementProp name=\"ThreadGroup.main_controller\" elementType=\"LoopController\">\n"
                + "          <boolProp name=\"LoopController.continue_forever\">false</boolProp>\n"
                + "          <intProp name=\"LoopController.loops\">-1</intProp>\n"
                + "        </elementProp>\n"
                + "        <stringProp name=\"ThreadGroup.num_threads\">" + request.concurrency() + "</stringProp>\n"
                + "        <stringProp name=\"ThreadGroup.ramp_time\">" + request.rampUpSeconds() + "</stringProp>\n"
                + "        <boolProp name=\"ThreadGroup.scheduler\">true</boolProp>\n"
                + "        <stringProp name=\"ThreadGroup.duration\">" + request.durationSeconds() + "</stringProp>\n"
                + "        <stringProp name=\"ThreadGroup.delay\">0</stringProp>\n"
                + "        <boolProp name=\"ThreadGroup.same_user_on_next_iteration\">true</boolProp>\n"
                + "      </ThreadGroup>\n";
    }

    private static String sampler(JmeterWorkloadRequest request) {
        URI target = target(request.url());
        return "        <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\" testname=\""
                + escape(request.method() + " " + request.url()) + "\" enabled=\"true\">\n"
                + "          <stringProp name=\"HTTPSampler.domain\">" + escape(target.getHost()) + "</stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.port\">" + port(target) + "</stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.protocol\">" + escape(target.getScheme()) + "</stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.contentEncoding\"></stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.path\">" + escape(path(target)) + "</stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.method\">" + escape(request.method()) + "</stringProp>\n"
                + body(request.body())
                + "          <boolProp name=\"HTTPSampler.follow_redirects\">true</boolProp>\n"
                + "          <boolProp name=\"HTTPSampler.auto_redirects\">false</boolProp>\n"
                + "          <boolProp name=\"HTTPSampler.use_keepalive\">true</boolProp>\n"
                + "          <boolProp name=\"HTTPSampler.DO_MULTIPART_POST\">false</boolProp>\n"
                + "          <stringProp name=\"HTTPSampler.embedded_url_re\"></stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.connect_timeout\">" + request.timeoutMs() + "</stringProp>\n"
                + "          <stringProp name=\"HTTPSampler.response_timeout\">" + request.timeoutMs() + "</stringProp>\n"
                + "        </HTTPSamplerProxy>\n"
                + "        <hashTree/>\n";
    }

    private static URI target(String url) {
        return URI.create(url);
    }

    private static String path(URI target) {
        String path = target.getRawPath();
        path = path == null || path.isBlank() ? "/" : path;
        return target.getRawQuery() == null ? path : path + "?" + target.getRawQuery();
    }

    private static String port(URI target) {
        return target.getPort() < 0 ? "" : Integer.toString(target.getPort());
    }

    private static String headers(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder xml = new StringBuilder(
                "        <HeaderManager guiclass=\"HeaderPanel\" testclass=\"HeaderManager\" "
                        + "testname=\"HTTP Header Manager\" enabled=\"true\">\n"
                        + "          <collectionProp name=\"HeaderManager.headers\">\n");
        headers.forEach((name, value) -> xml.append("            <elementProp name=\"").append(escape(name))
                .append("\" elementType=\"Header\">\n              <stringProp name=\"Header.name\">")
                .append(escape(name)).append("</stringProp>\n              <stringProp name=\"Header.value\">")
                .append(escape(value)).append("</stringProp>\n            </elementProp>\n"));
        return xml.append("          </collectionProp>\n        </HeaderManager>\n        <hashTree/>\n").toString();
    }

    private static String body(String body) {
        if (body == null || body.isBlank()) {
            return "          <boolProp name=\"HTTPSampler.postBodyRaw\">false</boolProp>\n"
                    + "          <elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\">\n"
                    + "            <collectionProp name=\"Arguments.arguments\"/>\n"
                    + "          </elementProp>\n";
        }
        return "          <boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>\n"
                + "          <elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\">\n"
                + "            <collectionProp name=\"Arguments.arguments\">\n"
                + "              <elementProp name=\"\" elementType=\"HTTPArgument\">\n"
                + "                <boolProp name=\"HTTPArgument.always_encode\">false</boolProp>\n"
                + "                <stringProp name=\"Argument.value\">" + escape(body) + "</stringProp>\n"
                + "                <stringProp name=\"Argument.metadata\">=</stringProp>\n"
                + "              </elementProp>\n"
                + "            </collectionProp>\n"
                + "          </elementProp>\n";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

}
