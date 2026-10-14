package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JmeterJmxRendererTest {

    @Test
    void evaluatesOnlyBoundCredentialExpressionsAndEncodesTheirRuntimeQueryValues() {
        String bound = "${__groovy(System.getenv('BOUND'))}";
        String unbound = "${__groovy(System.getenv('UNBOUND'))}";
        String encoded = java.net.URLEncoder.encode(bound, java.nio.charset.StandardCharsets.UTF_8);
        String literal = java.net.URLEncoder.encode(unbound, java.nio.charset.StandardCharsets.UTF_8);
        JmeterWorkloadRequest request = new JmeterWorkloadRequest(Path.of("run"), "query", "jmeter", "PUT",
                "http://localhost/query?token=prefix+" + encoded + "+suffix&literal=" + literal + "&value=a%2Bb%26c",
                Map.of(), "{}", Map.of("BOUND", "synthetic-secret"), 2500, 1, 0, 1);

        String xml = new JmeterJmxRenderer().render(request);

        assertThat(xml).contains("token=prefix+${__urlencode(${__groovy(System.getenv(&apos;BOUND&apos;))})}+suffix")
                .contains("literal=" + literal, "value=a%2Bb%26c", "HTTPSampler.postBodyRaw\">true")
                .doesNotContain("synthetic-secret", "System.getenv(&apos;UNBOUND&apos;)");
    }

    @Test
    void rendersExecutableJmeterElementsAndDecomposesTargetUrl() {
        JmeterWorkloadRequest request = new JmeterWorkloadRequest(
                Path.of("run"), "plan & one", "jmeter", "GET",
                "http://localhost:8080/api/users?active=true", Map.of("X-Test", "a&b"),
                null, Map.of(), 2500, 2, 1, 3);

        String xml = new JmeterJmxRenderer().render(request);

        assertThat(xml)
                .contains("<TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\"")
                .contains("<ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\"")
                .contains("<HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\"")
                .contains("<stringProp name=\"HTTPSampler.domain\">localhost</stringProp>")
                .contains("<stringProp name=\"HTTPSampler.port\">8080</stringProp>")
                .contains("<stringProp name=\"HTTPSampler.protocol\">http</stringProp>")
                .contains("<stringProp name=\"HTTPSampler.path\">/api/users?active=true</stringProp>")
                .contains("elementType=\"Header\"")
                .contains("plan &amp; one", "a&amp;b");
    }
}
