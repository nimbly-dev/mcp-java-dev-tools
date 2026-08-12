package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Implements explicit, packaged, then exact-local artifact resolution.
 */
public final class DeterministicJvmLifecycleArtifactResolver
        implements JvmLifecycleArtifactResolver {

    private static final String HELPER_MAIN = "com.nimbly.mcpjavadevtools.attach.JvmAttachMain";
    private static final String AGENT_CLASS =
            "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent";
    private static final String HELPER_LOCAL_NAME =
            "mcp-java-dev-tools-core-jvm-attach-0.1.8.jar";
    private static final String AGENT_LOCAL_NAME =
            "mcp-java-dev-tools-agent-0.1.8-all.jar";

    private final JvmLifecycleArtifactPolicy policy;

    /** Creates a resolver for one immutable path policy. */
    public DeterministicJvmLifecycleArtifactResolver(JvmLifecycleArtifactPolicy policy) {
        this.policy = policy;
    }

    @Override
    public JvmLifecycleArtifactResolution resolve(JvmLifecycleArtifactKind kind) {
        String configured = overrideValue(kind);
        if (configured != null && !configured.isBlank()) {
            try {
                return validate(kind, Path.of(configured.trim()).toAbsolutePath().normalize());
            } catch (RuntimeException exception) {
                return invalid(kind);
            }
        }
        Path packaged = policy.packagedDirectory().resolve("sidecar").resolve(kind.packagedName());
        if (Files.exists(packaged)) {
            return validate(kind, packaged);
        }
        Path local = localPath(kind);
        if (Files.exists(local)) {
            return validate(kind, local);
        }
        return JvmLifecycleArtifactResolution.blocked(kind.unavailableReason());
    }

    private String overrideValue(JvmLifecycleArtifactKind kind) {
        return kind == JvmLifecycleArtifactKind.HELPER
                ? policy.helperOverride() : policy.agentOverride();
    }

    private Path localPath(JvmLifecycleArtifactKind kind) {
        String name = kind == JvmLifecycleArtifactKind.HELPER
                ? HELPER_LOCAL_NAME : AGENT_LOCAL_NAME;
        String module = kind == JvmLifecycleArtifactKind.HELPER
                ? "core-jvm-attach" : "core-probe";
        return policy.localDevelopmentRoot()
                .resolve("java-agent").resolve("core").resolve(module)
                .resolve("target").resolve(name);
    }

    private static JvmLifecycleArtifactResolution validate(
            JvmLifecycleArtifactKind kind,
            Path path) {
        if (!Files.isRegularFile(path)) {
            return JvmLifecycleArtifactResolution.blocked(kind.unavailableReason());
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            boolean compatible = kind == JvmLifecycleArtifactKind.HELPER
                    ? validHelper(jar) : validAgent(jar);
            return compatible
                    ? JvmLifecycleArtifactResolution.resolved(path)
                    : invalid(kind);
        } catch (IOException | RuntimeException exception) {
            return invalid(kind);
        }
    }

    private static JvmLifecycleArtifactResolution invalid(JvmLifecycleArtifactKind kind) {
        String reason = kind == JvmLifecycleArtifactKind.HELPER
                ? "attach_helper_unavailable" : "agent_artifact_invalid";
        return JvmLifecycleArtifactResolution.blocked(reason);
    }

    private static boolean validHelper(JarFile jar) throws IOException {
        var manifest = jar.getManifest();
        return manifest != null
                && HELPER_MAIN.equals(manifest.getMainAttributes().getValue("Main-Class"))
                && jar.getJarEntry(HELPER_MAIN.replace('.', '/') + ".class") != null;
    }

    private static boolean validAgent(JarFile jar) throws IOException {
        var manifest = jar.getManifest();
        if (manifest == null) {
            return false;
        }
        var attributes = manifest.getMainAttributes();
        String entry = AGENT_CLASS.replace('.', '/') + ".class";
        return AGENT_CLASS.equals(attributes.getValue("Premain-Class"))
                && AGENT_CLASS.equals(attributes.getValue("Agent-Class"))
                && jar.getJarEntry(entry) != null;
    }
}
