package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies output redaction and bounded, atomic filesystem behavior. */
class ArtifactSafetyTest {

    @TempDir
    Path workspace;

    @Test
    void textWritesAreAtomicAndBounded() throws Exception {
        ArtifactJsonStore store = new ArtifactJsonStore(new ObjectMapper());
        Path target = workspace.resolve("nested/replay.sh");

        store.writeText(target, "safe\n");

        assertThat(Files.readString(target)).isEqualTo("safe\n");
        try (var entries = Files.list(target.getParent())) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
        assertThatThrownBy(() -> store.writeText(target, "x".repeat(4 * 1024 * 1024 + 1)))
                .isInstanceOf(ArtifactOperationException.class)
                .hasMessageContaining("write limit");
    }

    @Test
    void resultDetailsRedactSecretsAndBoundCollections() {
        ArtifactManagementResult result = ArtifactManagementResult.success(
                ArtifactType.PROBE_CONFIG,
                ArtifactAction.READ,
                Map.of(
                        "authorization", "Bearer very-secret",
                        "nested", Map.of("password", "p@ss", "items", List.of("a", "b"))));

        assertThat(result.details()).containsEntry("authorization", "[REDACTED]");
        assertThat(result.details().get("nested").toString()).contains("[REDACTED]");
    }

    @Test
    void primaryJsonArtifactsAreRedactedBeforePersistence() throws Exception {
        ArtifactJsonStore store = new ArtifactJsonStore(new ObjectMapper());
        Path path = workspace.resolve(".mcpjvm/projects.json");
        ObjectNode artifact = new ObjectMapper().createObjectNode();
        artifact.put("token", "do-not-persist");
        artifact.put("credentialRef", "security.api");
        artifact.putObject("defaults").put("password", "also-do-not-persist");

        store.write(path, artifact);

        String persisted = Files.readString(path);
        assertThat(persisted).doesNotContain("do-not-persist", "also-do-not-persist")
                .contains("[REDACTED]").contains("security.api");
    }
}
