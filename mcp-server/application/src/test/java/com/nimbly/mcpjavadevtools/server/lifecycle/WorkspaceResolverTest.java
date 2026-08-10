package com.nimbly.mcpjavadevtools.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class WorkspaceResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void commandLineWorkspaceTakesPrecedenceOverEnvironment() {
        Path argumentRoot = temporaryDirectory.resolve("argument-root");
        Path environmentRoot = temporaryDirectory.resolve("environment-root");
        WorkspaceSnapshot snapshot = WorkspaceResolver.initial(
                new DefaultApplicationArguments("--workspace-root=" + argumentRoot),
                Map.of("MCP_WORKSPACE_ROOT", environmentRoot.toString()),
                temporaryDirectory);

        assertThat(snapshot.root()).isEqualTo(argumentRoot.toAbsolutePath());
        assertThat(snapshot.source()).isEqualTo(WorkspaceSource.ARG);
        assertThat(snapshot.reasonCode()).isNull();
    }

    @Test
    void multipleCanonicalRootsProduceDeterministicAmbiguity() throws Exception {
        Path firstRoot = createCanonicalWorkspace("first");
        Path secondRoot = createCanonicalWorkspace("second");
        WorkspaceSnapshot snapshot = WorkspaceResolver.fromRoots(List.of(
                new McpSchema.Root(firstRoot.toUri().toString(), "first"),
                new McpSchema.Root(secondRoot.toUri().toString(), "second")));

        assertThat(snapshot.root()).isNull();
        assertThat(snapshot.source()).isEqualTo(WorkspaceSource.AMBIGUOUS);
        assertThat(snapshot.reasonCode()).isEqualTo("workspace_context_ambiguous");
    }

    @Test
    void invalidConfiguredWorkspaceProducesAStableReasonCode() {
        WorkspaceSnapshot snapshot = WorkspaceResolver.initial(
                new DefaultApplicationArguments("--workspace-root=invalid\u0000workspace"),
                Map.of(),
                temporaryDirectory);

        assertThat(snapshot.root()).isNull();
        assertThat(snapshot.source()).isEqualTo(WorkspaceSource.ARG);
        assertThat(snapshot.reasonCode()).isEqualTo("workspace_context_invalid");
    }

    @Test
    void transportFailureMarksRootsAsUnavailableWithoutDiscardingTheWorkspace() {
        Path root = temporaryDirectory.resolve("workspace");
        WorkspaceContext context = new WorkspaceContext(
                new WorkspaceSnapshot(root, WorkspaceSource.ROOTS, null, "available"));

        context.refreshFrom(new FailingRootsExchange(new McpTransportException("disconnected")));

        assertThat(context.snapshot().root()).isEqualTo(root);
        assertThat(context.snapshot().rootDiscoveryStatus()).isEqualTo("unavailable");
    }

    @Test
    void unexpectedRootsFailureIsNotClassifiedAsTransportUnavailability() {
        WorkspaceContext context = new WorkspaceContext(new WorkspaceSnapshot(
                temporaryDirectory,
                WorkspaceSource.ROOTS,
                null,
                "available"));
        IllegalStateException failure = new IllegalStateException("test failure");

        assertThatThrownBy(() -> context.refreshFrom(new FailingRootsExchange(failure)))
                .isSameAs(failure);
    }

    private Path createCanonicalWorkspace(String name) throws Exception {
        Path workspace = temporaryDirectory.resolve(name);
        Path metadataDirectory = workspace.resolve(".mcpjvm");
        Files.createDirectories(metadataDirectory);
        Files.createFile(metadataDirectory.resolve("probe-config.json"));
        return workspace;
    }

    private static class FailingRootsExchange extends McpSyncServerExchange {

        private final RuntimeException failure;

        private FailingRootsExchange(RuntimeException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public McpSchema.ListRootsResult listRoots() {
            throw failure;
        }
    }
}
