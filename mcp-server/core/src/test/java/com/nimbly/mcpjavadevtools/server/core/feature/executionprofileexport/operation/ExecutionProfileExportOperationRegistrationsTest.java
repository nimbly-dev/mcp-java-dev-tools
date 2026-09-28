package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionProfileExportOperationRegistrationsTest {

    private static final OperationId EXPORT = OperationId.of("execution_profile_export.export");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void confirmedCdeExportInvokesArtifactOwnerOnceWithExactInputAndRedactsResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ArtifactManagementRequest> received = new AtomicReference<>();
        ExecutionExportArtifactGateway gateway = request -> {
            calls.incrementAndGet();
            received.set(request);
            return ArtifactManagementResult.success(ArtifactType.EXECUTION_EXPORT,
                    ArtifactAction.GENERATE,
                    Map.of("exportId", "fixture-export", "authorization", "fixture-secret"));
        };
        OperationRegistration<?, ?> registration =
                ExecutionProfileExportOperationRegistrations.create(gateway, JSON).getFirst();
        OperationDirectory directory = directory(registration);
        var input = JSON.readTree("""
                {"projectName":"demo","exportId":"fixture-export","executionProfile":"smoke",
                 "mode":"ps1","includeRuntimeStartup":false,"includeHealthcheckGate":false}
                """);

        var unconfirmed = directory.execute(new OperationInvocation(EXPORT, input, false));
        var confirmed = directory.execute(new OperationInvocation(EXPORT, input, true));

        assertThat(unconfirmed.status()).isEqualTo(OperationExecutionStatus.CONFIRMATION_REQUIRED);
        assertThat(confirmed.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(calls).hasValue(1);
        assertThat(received.get().artifactType()).isEqualTo(ArtifactType.EXECUTION_EXPORT);
        assertThat(received.get().action()).isEqualTo(ArtifactAction.GENERATE);
        assertThat(received.get().input().path("projectName").asText()).isEqualTo("demo");
        assertThat(received.get().input().path("executionProfile").asText()).isEqualTo("smoke");
        assertThat(received.get().input().path("includeResolvedSecrets").asBoolean()).isFalse();
        assertThat(received.get().input().path("includeRuntimeStartup").asBoolean()).isFalse();
        assertThat(confirmed.result().path("resultType").asText()).isEqualTo("execution_profile_export");
        assertThat(confirmed.result().path("details").path("authorization").asText())
                .isEqualTo("***REDACTED***");
        assertThat(confirmed.result().path("details").has("artifactType")).isFalse();
        assertThat(registration.descriptor().executableOwner())
                .isEqualTo("com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations");
    }

    @Test
    void missingProfileResultRemainsDeterministicAndInvalidModeNeverReachesOwner() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ExecutionExportArtifactGateway gateway = request -> {
            calls.incrementAndGet();
            return new ArtifactManagementResult("report", "execution_profile_not_found",
                    "execution_profile_not_found", null, null,
                    "Selected execution profile is unavailable", Map.of(), Map.of());
        };
        OperationDirectory directory = directory(
                ExecutionProfileExportOperationRegistrations.create(gateway, JSON).getFirst());

        var missing = directory.execute(new OperationInvocation(EXPORT,
                JSON.readTree("{" + "\"executionProfile\":\"absent\",\"mode\":\"ps1\"}"), true));
        var invalid = directory.execute(new OperationInvocation(EXPORT,
                JSON.readTree("{" + "\"executionProfile\":\"absent\",\"mode\":\"invalid\"}"), true));

        assertThat(missing.result().path("reasonCode").asText()).isEqualTo("execution_profile_not_found");
        assertThat(invalid.status()).isEqualTo(OperationExecutionStatus.INVALID_INPUT);
        assertThat(calls).hasValue(1);
    }

    private static OperationDirectory directory(OperationRegistration<?, ?> registration) {
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        return new OperationDirectory(List.of(registration),
                new OperationManifestDocument(all.version(), Map.of(EXPORT, all.operations().get(EXPORT))), JSON);
    }
}
