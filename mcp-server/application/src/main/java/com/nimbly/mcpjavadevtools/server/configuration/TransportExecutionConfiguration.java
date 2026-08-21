package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.DefaultTransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.impl.ExecuteTransportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy.ProbeRegistryTransportExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy.TransportExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.custom.CustomTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.grpc.GrpcTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRedirectResponseExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpSensitiveDataRedactor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.kafka.KafkaTransportProvider;
import com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute.TransportExecuteMcpSchemaPostProcessor;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the complete Transport Execution Core Feature. */
@Configuration
@EnableConfigurationProperties(TransportExecutionConfigurationProperties.class)
public class TransportExecutionConfiguration {

    @Bean
    TransportExecutionPolicy transportExecutionPolicy(ProbeRegistryProvider registryProvider) {
        return new ProbeRegistryTransportExecutionPolicy(registryProvider);
    }

    @Bean
    HttpTransportSafetyPolicy transportExecutionHttpSafetyPolicy(
            TransportExecutionConfigurationProperties properties) {
        return new HttpTransportSafetyPolicy(properties.getHttp().getAllowedHosts());
    }

    @Bean
    HttpClient transportExecutionHttpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    HttpSensitiveDataRedactor transportExecutionHttpRedactor() {
        return new HttpSensitiveDataRedactor();
    }

    @Bean
    HttpRequestValidator transportExecutionHttpRequestValidator(
            HttpTransportSafetyPolicy safetyPolicy,
            HttpSensitiveDataRedactor redactor,
            ObjectMapper objectMapper) {
        return new HttpRequestValidator(safetyPolicy, redactor, objectMapper);
    }

    @Bean
    HttpRedirectResponseExecutor transportExecutionHttpRedirectResponseExecutor(
            HttpClient httpClient,
            HttpTransportSafetyPolicy safetyPolicy,
            HttpSensitiveDataRedactor redactor) {
        return new HttpRedirectResponseExecutor(httpClient, safetyPolicy, redactor);
    }

    @Bean
    HttpTransportProvider transportExecutionHttpProvider(
            HttpRequestValidator validator,
            HttpRedirectResponseExecutor executor) {
        return new HttpTransportProvider(validator, executor);
    }

    @Bean
    GrpcTransportProvider transportExecutionGrpcProvider() {
        return new GrpcTransportProvider();
    }

    @Bean
    KafkaTransportProvider transportExecutionKafkaProvider() {
        return new KafkaTransportProvider();
    }

    @Bean
    CustomTransportProvider transportExecutionCustomProvider() {
        return new CustomTransportProvider();
    }

    @Bean
    TransportProviderRegistry transportExecutionProviderRegistry(List<TransportProvider> providers) {
        return new TransportProviderRegistry(providers);
    }

    @Bean
    ExecuteTransportAction executeTransportAction(
            TransportExecutionPolicy policy,
            TransportProviderRegistry providers) {
        return new ExecuteTransportAction(policy, providers);
    }

    @Bean
    TransportExecutionFeature transportExecutionFeature(List<TransportExecutionActionHandler> handlers) {
        return new DefaultTransportExecutionFeature(handlers);
    }

    @Bean
    static BeanPostProcessor transportExecutionMcpSchemaPostProcessor() {
        return new TransportExecuteMcpSchemaPostProcessor();
    }
}
