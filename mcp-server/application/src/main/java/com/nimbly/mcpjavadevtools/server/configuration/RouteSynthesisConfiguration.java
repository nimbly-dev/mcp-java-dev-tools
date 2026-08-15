package com.nimbly.mcpjavadevtools.server.configuration;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.DefaultRouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.RouteSynthesisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.createrecipe.CreateRecipeCollaborators;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.ClassMethodsAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.CreateRecipeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.DiscoverHandlersAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl.InferTargetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.DefaultRouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication.RouteSynthesisAuthenticationResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.JavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.RouteSynthesisHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.SpringHttpHandlerDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking.DeterministicRouteTargetRanker;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking.RouteTargetRanker;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.routing.RouteSynthesisProbeRouteResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeEvidenceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.runtime.RouteSynthesisRuntimeMappingsProvider;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.DefaultSynthesizerRegistry;
import com.nimbly.mcpjavadevtools.server.core.synthesis.registry.SynthesizerRegistry;
import com.nimbly.mcpjavadevtools.server.core.synthesis.springhttp.SpringHttpSynthesizer;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceContext;
import com.nimbly.mcpjavadevtools.server.lifecycle.WorkspaceSnapshot;
import com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis.RouteSynthesisMcpSchemaPostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.net.http.HttpClient;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-only Spring composition for the complete Route Synthesis graph.
 */
@Configuration
@EnableConfigurationProperties({
        RouteSynthesisConfigurationProperties.class,
        RouteSynthesisRuntimeMappingsProperties.class})
public class RouteSynthesisConfiguration {

    @Bean
    static BeanPostProcessor routeSynthesisMcpSchemaPostProcessor() {
        return new RouteSynthesisMcpSchemaPostProcessor();
    }

    /** Binds the current Application workspace snapshot into a Core contract. */
    @Bean
    RouteSynthesisWorkspaceProvider routeSynthesisWorkspaceProvider(WorkspaceContext context) {
        return () -> workspaceSnapshot(context.snapshot());
    }

    /** Binds source discovery to the current workspace without leaking Spring into Core. */
    @Bean
    JavaSourceDiscovery routeSynthesisJavaSourceDiscovery(RouteSynthesisWorkspaceProvider provider) {
        return new RouteSynthesisWorkspaceJavaSourceDiscovery(provider);
    }

    /** Supplies source-backed Spring HTTP mapping discovery. */
    @Bean
    RouteSynthesisHandlerDiscovery routeSynthesisHandlerDiscovery(JavaSourceDiscovery discovery) {
        return new SpringHttpHandlerDiscovery(discovery);
    }

    /** Adapts the public Probe target resolver boundary to Route Synthesis. */
    @Bean
    RouteSynthesisProbeRouteResolver routeSynthesisProbeRouteResolver(
            com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver resolver) {
        return (probeId, baseUrl) -> resolveProbeRoute(resolver, probeId, baseUrl);
    }

    /** Adapts the public Probe Feature boundary to bounded runtime evidence. */
    @Bean
    RouteSynthesisRuntimeEvidenceProvider routeSynthesisRuntimeEvidenceProvider(ProbeFeature probeFeature) {
        return new RouteSynthesisProbeRuntimeEvidenceAdapter(probeFeature);
    }

    /** Adapts bounded Spring Actuator runtime mappings without leaking HTTP into Core. */
    @Bean
    RouteSynthesisRuntimeMappingsProvider routeSynthesisRuntimeMappingsProvider(
            RouteSynthesisRuntimeMappingsProperties properties) {
        return new RouteSynthesisRuntimeMappingsHttpAdapter(HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), properties.getRuntimeMappingsAllowedHosts(),
                properties.getRuntimeMappingsMaxResponseBytes());
    }

    /** Supplies the explicit candidate ordering policy. */
    @Bean
    RouteTargetRanker routeTargetRanker() {
        return new DeterministicRouteTargetRanker();
    }

    /** Supplies safe authentication metadata resolution. */
    @Bean
    RouteSynthesisAuthenticationResolver routeSynthesisAuthenticationResolver() {
        return new DefaultRouteSynthesisAuthenticationResolver();
    }

    /** Supplies the v0.1.9 built-in Spring HTTP registry and frozen module policy. */
    @Bean
    SynthesizerRegistry routeSynthesisSynthesizerRegistry(
            RouteSynthesisConfigurationProperties properties) {
        return new DefaultSynthesizerRegistry(
                new SpringHttpSynthesizer(), properties.configuredExternalModuleCount());
    }

    /** Creates the real infer_target action. */
    @Bean
    InferTargetAction inferTargetAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            JavaSourceDiscovery sourceDiscovery,
            RouteSynthesisProbeRouteResolver routeResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidence,
            RouteTargetRanker ranker) {
        return new InferTargetAction(
                workspaceProvider, sourceDiscovery, routeResolver, runtimeEvidence, ranker);
    }

    /** Creates the real class_methods action. */
    @Bean
    ClassMethodsAction classMethodsAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            JavaSourceDiscovery sourceDiscovery,
            RouteSynthesisProbeRouteResolver routeResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidence) {
        return new ClassMethodsAction(
                workspaceProvider, sourceDiscovery, routeResolver, runtimeEvidence);
    }

    /** Creates the real discover_handlers action. */
    @Bean
    DiscoverHandlersAction discoverHandlersAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            RouteSynthesisHandlerDiscovery handlerDiscovery,
            RouteSynthesisProbeRouteResolver routeResolver,
            RouteSynthesisRuntimeEvidenceProvider runtimeEvidence) {
        return new DiscoverHandlersAction(
                workspaceProvider, handlerDiscovery, routeResolver, runtimeEvidence);
    }

    /** Creates the real create_recipe action. */
    @Bean
    CreateRecipeAction createRecipeAction(
            RouteSynthesisWorkspaceProvider workspaceProvider,
            RouteSynthesisHandlerDiscovery handlerDiscovery,
            RouteSynthesisAuthenticationResolver authenticationResolver,
            SynthesizerRegistry synthesizerRegistry,
            CreateRecipeCollaborators collaborators) {
        return new CreateRecipeAction(
                workspaceProvider, handlerDiscovery, authenticationResolver, synthesizerRegistry, collaborators);
    }

    /** Composes the bounded create_recipe runtime collaborators. */
    @Bean
    CreateRecipeCollaborators routeSynthesisCreateRecipeCollaborators(
                    RouteSynthesisRuntimeMappingsProvider runtimeMappingsProvider,
                    RouteSynthesisRuntimeEvidenceProvider runtimeEvidenceProvider,
                    RouteSynthesisProbeRouteResolver probeRouteResolver) {
        return new CreateRecipeCollaborators(runtimeMappingsProvider, runtimeEvidenceProvider,
                        new com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.template
                                .DefaultRouteSynthesisRecipeTemplateRenderer(), probeRouteResolver);
    }

    /** Assembles the four-action Core Feature before MCP transport registration. */
    @Bean
    RouteSynthesisFeature routeSynthesisFeature(List<RouteSynthesisActionHandler> handlers) {
        return new DefaultRouteSynthesisFeature(handlers);
    }

    private Optional<RouteSynthesisWorkspaceSnapshot> workspaceSnapshot(WorkspaceSnapshot snapshot) {
        if (snapshot == null || snapshot.root() == null) {
            return Optional.empty();
        }
        return Optional.of(new RouteSynthesisWorkspaceSnapshot(snapshot.root()));
    }

    private RouteSynthesisProbeRouteResolution resolveProbeRoute(
            com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver resolver,
            String probeId,
            String baseUrl) {
        ProbeTargetResolution resolution = resolver.resolve(new ProbeTargetSelector(probeId, baseUrl));
        if (resolution instanceof ResolvedProbeTarget resolved) {
            return RouteSynthesisProbeRouteResolution.resolved(resolved.target().baseUrl().toString());
        }
        ProbeResult result = ((com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget)
                resolution).result();
        return RouteSynthesisProbeRouteResolution.unresolved(result.reasonCode().value());
    }
}
