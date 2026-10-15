package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.nio.file.ExtendedOpenOption;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ArtifactJsonStoreRunSetTest {
    @TempDir
    Path workspace;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void failedLastMoveRestoresAllEarlierFilesExactlyAndReleasesStaging() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArtifactJsonStore store = new ArtifactJsonStore(mapper);
        Map<Path, JsonNode> original = new LinkedHashMap<>();
        for (String name : List.of("correlation.json", "context.resolved.json", "evidence.json", "execution.result.json")) {
            original.put(workspace.resolve(name), mapper.createObjectNode().put("status", "pass"));
        }
        store.writeSet(original);
        Map<Path, byte[]> before = new LinkedHashMap<>();
        Map<Path, JsonNode> replacement = new LinkedHashMap<>();
        for (Path target : original.keySet()) {
            before.put(target, Files.readAllBytes(target));
            replacement.put(target, mapper.createObjectNode().put("status", "fail"));
        }
        Path canonical = workspace.resolve("execution.result.json");
        try (var held = FileChannel.open(canonical, Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))) {
            assertThat(held.isOpen()).isTrue();
            assertThatThrownBy(() -> store.writeSet(replacement)).isInstanceOfSatisfying(ArtifactOperationException.class,
                    failure -> assertThat(failure.reasonCode()).isEqualTo("artifact_write_failed"));
            for (var entry : before.entrySet()) {
                assertThat(Files.readAllBytes(entry.getKey())).as(entry.getKey().getFileName().toString()).isEqualTo(entry.getValue());
            }
        }
        try (var paths = Files.list(workspace)) {
            assertThat(paths.toList()).containsExactlyInAnyOrderElementsOf(original.keySet());
        }
        store.writeSet(replacement);
        assertThat(mapper.readTree(canonical.toFile()).path("status").asText()).isEqualTo("fail");
    }
}
