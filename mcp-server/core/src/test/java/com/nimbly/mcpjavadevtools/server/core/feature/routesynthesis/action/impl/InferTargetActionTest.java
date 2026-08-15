package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.FileSystemJavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.ranking.DeterministicRouteTargetRanker;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.workspace.RouteSynthesisWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InferTargetActionTest {

    @TempDir
    Path temp;

    @Test
    void infersAnExactTargetWithRuntimeValidatedLine() throws IOException {
        Path project = writeSource(temp.resolve("project"), "example", "Work", "beta", "alpha");
        InferTargetAction action = action(Optional.of(snapshot()));

        RouteSynthesisResult result = action.execute(new InferTargetRequest(
                project.toString(),
                List.of(),
                "example.Work",
                "beta",
                null,
                null,
                null,
                "http://probe"));

        assertThat(result.status()).isEqualTo("ok");
        InferTargetResult output = (InferTargetResult) result.actionResult();
        assertThat(output.candidates()).hasSize(1);
        assertThat(output.candidates().get(0).methodName()).isEqualTo("beta");
        assertThat(output.candidates().get(0).lineSelectionStatus()).isEqualTo("validated");
    }

    @Test
    void reportsAmbiguousExactTargetsDeterministically() throws IOException {
        Path first = writeSource(temp.resolve("first"), "example", "Work", "beta");
        Path second = writeSource(temp.resolve("second"), "example", "Work", "beta");
        InferTargetAction action = action(Optional.of(snapshot()));

        RouteSynthesisResult result = action.execute(new InferTargetRequest(
                first.toString(),
                List.of(second.toString()),
                "example.Work",
                "beta",
                null,
                null,
                null,
                "http://probe"));

        assertThat(result.status()).isEqualTo("target_ambiguous");
        InferTargetResult output = (InferTargetResult) result.actionResult();
        assertThat(output.candidates()).hasSize(2);
        assertThat(output.candidates().get(0).file())
                .isLessThan(output.candidates().get(1).file());
    }

    @Test
    void failsClosedWhenProjectEscapesTheWorkspace() {
        InferTargetAction action = action(Optional.of(snapshot()));

        RouteSynthesisResult result = action.execute(new InferTargetRequest(
                temp.getParent().toString(),
                List.of(),
                "example.Work",
                "beta",
                null,
                null,
                null,
                null));

        assertThat(result.reasonCode()).isEqualTo("project_selector_invalid");
    }

    @Test
    void reportsRuntimeLineResolutionFailure() throws IOException {
        Path project = writeSource(temp.resolve("project"), "example", "Work", "beta");
        InferTargetAction action = new InferTargetAction(
                () -> Optional.of(snapshot()),
                new FileSystemJavaSourceDiscovery(snapshot()),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.resolved(baseUrl),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.unresolved(
                        "runtime_line_unresolved"),
                new DeterministicRouteTargetRanker());

        RouteSynthesisResult result = action.execute(new InferTargetRequest(
                project.toString(),
                List.of(),
                "example.Work",
                "beta",
                null,
                null,
                null,
                "http://probe"));

        assertThat(result.reasonCode()).isEqualTo("runtime_line_unresolved");
        assertThat(result.failedStep()).isEqualTo("line_validation");
    }

    @Test
    void reportsMissingWorkspaceBeforeReadingSource() {
        InferTargetAction action = new InferTargetAction(
                Optional::empty,
                new FileSystemJavaSourceDiscovery(snapshot()),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.unresolved("missing"),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.unresolved("missing"),
                new DeterministicRouteTargetRanker());

        RouteSynthesisResult result = action.execute(new InferTargetRequest(
                "project",
                List.of(),
                "example.Work",
                null,
                null,
                null,
                null,
                null));

        assertThat(result.reasonCode()).isEqualTo("workspace_context_missing");
    }

    private InferTargetAction action(Optional<RouteSynthesisWorkspaceSnapshot> workspace) {
        RouteSynthesisWorkspaceSnapshot snapshot = workspace.orElseThrow();
        RouteSynthesisWorkspaceProvider provider = () -> workspace;
        return new InferTargetAction(
                provider,
                new FileSystemJavaSourceDiscovery(snapshot),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.resolved(baseUrl),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.resolved(start),
                new DeterministicRouteTargetRanker());
    }

    private RouteSynthesisWorkspaceSnapshot snapshot() {
        return new RouteSynthesisWorkspaceSnapshot(temp);
    }

    private Path writeSource(Path root, String packageName, String className, String... methods)
            throws IOException {
        Path source = root.resolve("src/main/java").resolve(packageName.replace('.', '/'));
        Files.createDirectories(source);
        StringBuilder text = new StringBuilder("package ").append(packageName).append(";\n")
                .append("public class ").append(className).append(" {\n");
        for (String method : methods) {
            text.append("    public boolean ").append(method).append("() {\n")
                    .append("        return true;\n")
                    .append("    }\n");
        }
        text.append("}\n");
        Path file = source.resolve(className + ".java");
        Files.writeString(file, text);
        return root;
    }
}


