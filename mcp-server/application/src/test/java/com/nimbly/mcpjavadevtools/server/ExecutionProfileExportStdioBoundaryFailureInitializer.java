package com.nimbly.mcpjavadevtools.server;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** Test-only packaged-jar composition hook for exercising the Application boundary failure path. */
public final class ExecutionProfileExportStdioBoundaryFailureInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String ACTIVATION_PROPERTY = "mcp605.stdio.boundary-failure";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        if (!Boolean.parseBoolean(System.getProperty(ACTIVATION_PROPERTY, "false"))) {
            return;
        }
        if (!(applicationContext instanceof GenericApplicationContext context)) {
            throw new IllegalStateException("MCPJVM-605 test initializer requires a generic application context");
        }
        context.registerBean(
                "mcp605ExecutionExportArtifactGateway",
                ExecutionExportArtifactGateway.class,
                () -> request -> {
                    throw new McpBoundaryException(
                            McpBoundaryFailureKind.CONFIGURATION_INVARIANT,
                            new IllegalStateException("mcp605-controlled-secret"));
                },
                definition -> definition.setPrimary(true));
    }
}
