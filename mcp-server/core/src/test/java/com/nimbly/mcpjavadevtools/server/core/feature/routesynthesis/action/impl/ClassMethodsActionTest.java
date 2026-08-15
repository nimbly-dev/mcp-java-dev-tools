package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.discovery.FileSystemJavaSourceDiscovery;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.classmethods.ClassMethodsResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.routing.RouteSynthesisProbeRouteResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.runtime.RouteSynthesisRuntimeLineResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.workspace.RouteSynthesisWorkspaceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassMethodsActionTest {

    @TempDir
    Path temp;

    @Test
    void returnsMethodsInStableSourceOrder() throws IOException {
        Path project = writeSource(temp.resolve("project"), "example", "Work", "alpha", "beta");
        ClassMethodsAction action = action();

        RouteSynthesisResult result = action.execute(new ClassMethodsRequest(
                project.toString(), List.of(), "example.Work", null, "http://probe"));

        assertThat(result.status()).isEqualTo("ok");
        ClassMethodsResult output = (ClassMethodsResult) result.actionResult();
        assertThat(output.target().methods())
                .extracting(method -> method.methodName())
                .containsExactly("alpha", "beta");
    }

    @Test
    void reportsMissingClassDeterministically() throws IOException {
        Path project = writeSource(temp.resolve("project"), "example", "Work", "alpha");

        RouteSynthesisResult result = action().execute(new ClassMethodsRequest(
                project.toString(), List.of(), "example.Missing", null, null));

        assertThat(result.resultType()).isEqualTo("class_methods");
        assertThat(result.reasonCode()).isEqualTo("class_not_found");
        assertThat(result.failedStep()).isEqualTo("target_inference");
    }

    @Test
    void reportsAmbiguousClassMatches() throws IOException {
        Path first = writeSource(temp.resolve("first"), "example", "Work", "alpha");
        Path second = writeSource(temp.resolve("second"), "example", "Work", "alpha");
        ClassMethodsAction action = new ClassMethodsAction(
                () -> Optional.of(snapshot()),
                new FileSystemJavaSourceDiscovery(snapshot()),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.resolved(baseUrl),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.resolved(start));

        RouteSynthesisResult result = action.execute(new ClassMethodsRequest(
                first.toString(), List.of(second.toString()), "example.Work", null, "http://probe"));

        assertThat(result.reasonCode()).isEqualTo("class_ambiguous");
        assertThat(result.status()).isEqualTo("class_ambiguous");
    }

    private ClassMethodsAction action() {
        RouteSynthesisWorkspaceSnapshot snapshot = snapshot();
        return new ClassMethodsAction(
                () -> Optional.of(snapshot),
                new FileSystemJavaSourceDiscovery(snapshot),
                (probeId, baseUrl) -> RouteSynthesisProbeRouteResolution.resolved(baseUrl),
                (key, start, end, route) -> RouteSynthesisRuntimeLineResolution.resolved(start));
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


