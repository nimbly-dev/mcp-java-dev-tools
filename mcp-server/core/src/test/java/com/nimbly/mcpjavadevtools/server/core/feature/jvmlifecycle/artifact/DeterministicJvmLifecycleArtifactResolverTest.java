package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicJvmLifecycleArtifactResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void prefersThePackagedArtifactOverTheExactLocalArtifact() throws Exception {
        Path packaged = tempDir.resolve("sidecar/jvm-attach-helper.jar");
        Path local = tempDir.resolve("repo/java-agent/core/core-jvm-attach/target/")
                .resolve("mcp-java-dev-tools-core-jvm-attach-0.1.8.jar");
        writeHelper(packaged);
        writeHelper(local);

        JvmLifecycleArtifactResolver resolver = resolver(null, null, tempDir, tempDir.resolve("repo"));

        assertThat(resolver.resolve(JvmLifecycleArtifactKind.HELPER).path())
                .isEqualTo(packaged.toAbsolutePath().normalize());
    }

    @Test
    void invalidExplicitOverrideDoesNotFallBack() {
        JvmLifecycleArtifactResolver resolver = resolver(
                tempDir.resolve("missing-helper.jar").toString(), null, tempDir, tempDir);

        assertThat(resolver.resolve(JvmLifecycleArtifactKind.HELPER).reasonCode())
                .isEqualTo("attach_helper_unavailable");
    }

    @Test
    void validatesAgentManifestAndClassEntry() throws Exception {
        Path agent = tempDir.resolve("agent.jar");
        writeAgent(agent, false);
        JvmLifecycleArtifactResolver resolver = resolver(null, agent.toString(), tempDir, tempDir);

        assertThat(resolver.resolve(JvmLifecycleArtifactKind.AGENT).reasonCode())
                .isEqualTo("agent_artifact_invalid");
    }

    private JvmLifecycleArtifactResolver resolver(
            String helper,
            String agent,
            Path packaged,
            Path local) {
        return new DeterministicJvmLifecycleArtifactResolver(
                new JvmLifecycleArtifactPolicy(helper, agent, packaged, local));
    }

    private static void writeHelper(Path path) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(
                "Main-Class", "com.nimbly.mcpjavadevtools.attach.JvmAttachMain");
        writeJar(path, manifest, "com/nimbly/mcpjavadevtools/attach/JvmAttachMain.class");
    }

    private static void writeAgent(Path path, boolean valid) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(
                "Premain-Class", "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent");
        if (valid) {
            manifest.getMainAttributes().putValue(
                    "Agent-Class", "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent");
        }
        writeJar(path, manifest,
                "com/nimbly/mcpjavadevtools/agent/bootstrap/ProbeAgent.class");
    }

    private static void writeJar(Path path, Manifest manifest, String entry) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry(entry));
            output.closeEntry();
        }
    }
}
