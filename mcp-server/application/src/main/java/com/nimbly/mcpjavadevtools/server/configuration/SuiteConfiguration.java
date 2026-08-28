package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.DefaultPerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.PerformanceSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.impl.ExecutePerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution.PerformancePlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.DefaultJmeterProcessRunner;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.DefaultRegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.RegressionSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.ExecuteRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.PreflightRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.DefaultSecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.SecuritySuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.impl.ExecuteSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution.SecurityPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the suite Features consumed only by execution orchestration. */
@Configuration
public class SuiteConfiguration {

    @Bean
    JmeterExecutableResolver jmeterExecutableResolver() {
        return new JmeterExecutableResolver(SuiteConfiguration::jmeterLocations);
    }

    private static List<String> jmeterLocations() {
        List<String> locations = new ArrayList<>();
        String home = System.getenv("MCP_JAVA_DEV_TOOLS_JMETER_HOME");
        if (home != null && !home.isBlank()) {
            locations.add(home);
        }
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String location : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!location.isBlank()) {
                    locations.add(location);
                }
            }
        }
        return List.copyOf(locations);
    }

    @Bean
    JmeterWorkloadExecutor jmeterWorkloadExecutor() {
        return new JmeterWorkloadExecutor(
                new JmeterJmxRenderer(), new DefaultJmeterProcessRunner(), new JmeterJtlCollector());
    }

    @Bean
    PerformancePlanExecutor performancePlanExecutor(
            JmeterExecutableResolver resolver,
            JmeterWorkloadExecutor workloadExecutor,
            ProbeFeature probe,
            TransportExecutionFeature transport) {
        return new PerformancePlanExecutor(resolver, workloadExecutor, probe, transport);
    }

    @Bean
    ExecutePerformancePlanAction executePerformancePlanAction(PerformancePlanExecutor executor) {
        return new ExecutePerformancePlanAction(executor);
    }

    @Bean
    PerformanceSuiteFeature performanceSuiteFeature(List<PerformanceSuiteActionHandler> handlers) {
        return new DefaultPerformanceSuiteFeature(handlers);
    }

    @Bean
    RegressionPlanPreflight regressionPlanPreflight() {
        return new RegressionPlanPreflight();
    }

    @Bean
    RegressionPlanExecutor regressionPlanExecutor(
            RegressionPlanPreflight preflight,
            TransportExecutionFeature transport,
            ObjectMapper mapper) {
        return new RegressionPlanExecutor(preflight, transport, mapper);
    }

    @Bean
    PreflightRegressionPlanAction preflightRegressionPlanAction(RegressionPlanPreflight preflight) {
        return new PreflightRegressionPlanAction(preflight);
    }

    @Bean
    ExecuteRegressionPlanAction executeRegressionPlanAction(RegressionPlanExecutor executor) {
        return new ExecuteRegressionPlanAction(executor);
    }

    @Bean
    RegressionSuiteFeature regressionSuiteFeature(List<RegressionSuiteActionHandler> handlers) {
        return new DefaultRegressionSuiteFeature(handlers);
    }

    @Bean
    SecurityKnowledgeCatalog securityKnowledgeCatalog() {
        return new SecurityKnowledgeCatalog();
    }

    @Bean
    SecurityPlanExecutor securityPlanExecutor(
            TransportExecutionFeature transport,
            SecurityKnowledgeCatalog knowledge,
            ProbeFeature probe) {
        return new SecurityPlanExecutor(transport, knowledge, probe);
    }

    @Bean
    ExecuteSecurityPlanAction executeSecurityPlanAction(SecurityPlanExecutor executor) {
        return new ExecuteSecurityPlanAction(executor);
    }

    @Bean
    SecuritySuiteFeature securitySuiteFeature(List<SecuritySuiteActionHandler> handlers) {
        return new DefaultSecuritySuiteFeature(handlers);
    }
}
