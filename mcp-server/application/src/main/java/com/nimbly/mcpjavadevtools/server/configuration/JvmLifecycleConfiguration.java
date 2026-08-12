package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.McpJavaDevToolsServerApplication;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.DefaultJvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.attach.impl.AttachAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.deactivate.impl.DeactivateAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.listjvms.impl.ListJvmsAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.DeterministicJvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact.JvmLifecycleArtifactResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.DefaultJvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper.JvmLifecycleHelper;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.JvmLifecycleExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.policy.ProbeHostPolicy;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring composition for the complete JVM lifecycle Core Feature.
 */
@Configuration
@EnableConfigurationProperties(JvmLifecycleConfigurationProperties.class)
public class JvmLifecycleConfiguration {

    @Bean
    JvmLifecycleArtifactPolicy jvmLifecycleArtifactPolicy(
            JvmLifecycleConfigurationProperties properties) {
        return new JvmLifecycleArtifactPolicy(
                properties.getArtifacts().getHelperJar(),
                properties.getArtifacts().getAgentJar(),
                packagedDirectory(),
                localDevelopmentRoot());
    }

    @Bean
    JvmLifecycleArtifactResolver jvmLifecycleArtifactResolver(JvmLifecycleArtifactPolicy policy) {
        return new DeterministicJvmLifecycleArtifactResolver(policy);
    }

    @Bean
    ProbeHostPolicy jvmLifecycleProbeHostPolicy(JvmLifecycleConfigurationProperties properties) {
        String configuredHosts = properties.getAllowedProbeHosts() == null
                ? "" : properties.getAllowedProbeHosts();
        Set<String> hosts = Arrays.stream(configuredHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new ProbeHostPolicy(hosts);
    }

    @Bean
    JvmLifecycleExecutionPolicy jvmLifecycleExecutionPolicy(
            JvmLifecycleConfigurationProperties properties,
            ProbeHostPolicy probeHostPolicy) {
        return JvmLifecycleExecutionPolicy.frozen(properties.getJavaBin(), probeHostPolicy);
    }

    @Bean
    JvmLifecycleHelper jvmLifecycleHelper(
            JvmLifecycleArtifactResolver artifacts,
            JvmLifecycleExecutionPolicy policy) {
        return new DefaultJvmLifecycleHelper(artifacts, policy);
    }

    @Bean
    ListJvmsAction listJvmsAction(JvmLifecycleHelper helper) {
        return new ListJvmsAction(helper);
    }

    @Bean
    AttachAction attachAction(
            JvmLifecycleHelper helper,
            JvmLifecycleArtifactResolver artifacts,
            ProbeHostPolicy probeHostPolicy) {
        return new AttachAction(helper, artifacts, probeHostPolicy);
    }

    @Bean
    DeactivateAction deactivateAction(
            JvmLifecycleHelper helper,
            JvmLifecycleArtifactResolver artifacts) {
        return new DeactivateAction(helper, artifacts);
    }

    @Bean
    JvmLifecycleFeature jvmLifecycleFeature(List<JvmLifecycleActionHandler> handlers) {
        return new DefaultJvmLifecycleFeature(handlers);
    }

    private static Path packagedDirectory() {
        try {
            Path location = Path.of(McpJavaDevToolsServerApplication.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (URISyntaxException | RuntimeException exception) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static Path localDevelopmentRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        Path candidate = current;
        for (int depth = 0; depth < 4 && candidate != null; depth++) {
            if (hasExactLocalArtifact(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return current;
    }

    private static boolean hasExactLocalArtifact(Path root) {
        Path helper = root.resolve("java-agent/core/core-jvm-attach/target")
                .resolve("mcp-java-dev-tools-core-jvm-attach-0.1.8.jar");
        Path agent = root.resolve("java-agent/core/core-probe/target")
                .resolve("mcp-java-dev-tools-agent-0.1.8-all.jar");
        return Files.isRegularFile(helper) || Files.isRegularFile(agent);
    }
}
