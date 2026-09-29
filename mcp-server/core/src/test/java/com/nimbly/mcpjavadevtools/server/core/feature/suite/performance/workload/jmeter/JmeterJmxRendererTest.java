package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JmeterJmxRendererTest {

    @Test
    void rendersExecutableJmeterElementsAndDecomposesTargetUrl() {
        JmeterWorkloadRequest request = new JmeterWorkloadRequest(
                Path.of("run"), "plan & one", "jmeter", "GET",
                "http://localhost:8080/api/users?active=true", Map.of("X-Test", "a&b"),
                null, 2500, 2, 1, 3);

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
