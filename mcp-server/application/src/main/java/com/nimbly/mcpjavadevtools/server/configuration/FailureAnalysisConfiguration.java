package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.DefaultFailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.AnalyzeTraceAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl.VerifyReproductionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceResponseMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.HttpFailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;
import com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis.FailureAnalysisMcpSchemaPostProcessor;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the complete Failure Analysis Core Feature. */
@Configuration
@EnableConfigurationProperties(FailureAnalysisConfigurationProperties.class)
public class FailureAnalysisConfiguration {

    @Bean
    static BeanPostProcessor failureAnalysisMcpSchemaPostProcessor() {
        return new FailureAnalysisMcpSchemaPostProcessor();
    }

    @Bean
    FailureAnalysisPolicy failureAnalysisPolicy(FailureAnalysisConfigurationProperties properties) {
        FailureAnalysisConfigurationProperties.Endpoint endpoint = properties.getEndpoint();
        return new FailureAnalysisPolicy(
                Duration.ofMillis(endpoint.getTimeoutMs()),
                endpoint.getMaximumTraceCharacters(),
                endpoint.getMaximumResponsePayloadBytes(),
                endpoint.getMaximumStringLength(),
                endpoint.getMaximumFrames(),
                endpoint.getMaximumSections());
    }

    @Bean
    FailureEvidenceClient failureEvidenceClient(FailureAnalysisPolicy policy) {
        return new HttpFailureEvidenceClient(new ObjectMapper(), policy);
    }

    @Bean
    FailureEvidenceResponseMapper failureEvidenceResponseMapper(FailureAnalysisPolicy policy) {
        return new FailureEvidenceResponseMapper(policy);
    }

    @Bean
    AnalyzeTraceAction analyzeTraceAction(
            FailureEvidenceClient client,
            FailureEvidenceResponseMapper responseMapper,
            FailureAnalysisPolicy policy) {
        return new AnalyzeTraceAction(client, responseMapper, policy);
    }

    @Bean
    VerifyReproductionAction verifyReproductionAction(
            FailureEvidenceClient client,
            FailureEvidenceResponseMapper responseMapper,
            FailureAnalysisPolicy policy) {
        return new VerifyReproductionAction(client, responseMapper, policy);
    }

    @Bean
    FailureAnalysisFeature failureAnalysisFeature(List<FailureAnalysisActionHandler> handlers) {
        return new DefaultFailureAnalysisFeature(handlers);
    }
}
