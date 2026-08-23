package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.DefaultExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.ExecutionProfileExportActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.impl.ExportExecutionProfileAction;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpSchemaPostProcessor;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the complete Execution Profile Export Feature. */
@Configuration
public class ExecutionProfileExportConfiguration {

    @Bean
    static ExecutionProfileExportMcpSchemaPostProcessor executionProfileExportMcpSchemaPostProcessor() {
        return new ExecutionProfileExportMcpSchemaPostProcessor();
    }

    @Bean
    ExportExecutionProfileAction exportExecutionProfileAction(
            ExecutionExportArtifactGateway artifactGateway, ObjectMapper objectMapper) {
        return new ExportExecutionProfileAction(artifactGateway, objectMapper);
    }

    @Bean
    ExecutionProfileExportFeature executionProfileExportFeature(
            List<ExecutionProfileExportActionHandler> handlers) {
        return new DefaultExecutionProfileExportFeature(handlers);
    }
}
