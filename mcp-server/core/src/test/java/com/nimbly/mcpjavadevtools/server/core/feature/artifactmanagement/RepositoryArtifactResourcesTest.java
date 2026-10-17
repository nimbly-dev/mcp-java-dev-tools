package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Validates retained guide Artifacts with their Java owners, without executing them. */
class RepositoryArtifactResourcesTest {
    @TempDir
    Path workspace;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void guideArtifactsRemainReadableAndValidUnderTheirCurrentOwners() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("mcp-server/pom.xml"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        Path guide = root.resolve("docs/guides/complete-project-example");
        try (var files = Files.walk(guide.resolve(".mcpjvm"))) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path destination = workspace.resolve(guide.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
        var support = new ArtifactManagementSupport(() -> Optional.of(workspace),
                new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        var project = new ProjectContextOperations(support);
        assertThat(project.validate(request(ArtifactType.PROJECT_CONTEXT, Map.of())).status()).isEqualTo("ok");
        var probe = new ProbeConfigOperations(support, () -> new ProbeRegistry(List.of()));
        assertThat(probe.validate(request(ArtifactType.PROBE_CONFIG, Map.of())).status()).isEqualTo("ok");
        var plans = new PlanOperations(support);
        assertThat(plans.validate(request(ArtifactType.PERFORMANCE_PLAN,
                Map.of("planName", "load-profile")), "performance").status()).isEqualTo("ok");
        // Step/external semantics, suite promotion, placeholders, correlation, and
        // Performance thresholds have dedicated assertions in the resource tests.
        // This test covers the Artifact owners, not executable-plan readiness.
        Path projectFile = workspace.resolve(".mcpjvm/example-project/projects.json");
        Files.copy(guide.resolve("examples/postgres-context-bindings.json"), projectFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(project.validate(request(ArtifactType.PROJECT_CONTEXT, Map.of())).status()).isEqualTo("ok");
    }

    private ArtifactManagementRequest request(ArtifactType type, Map<String, Object> fields) {
        ObjectNode input = mapper.valueToTree(fields);
        input.put("projectName", "example-project");
        return new ArtifactManagementRequest(type, ArtifactAction.VALIDATE, input);
    }
}
