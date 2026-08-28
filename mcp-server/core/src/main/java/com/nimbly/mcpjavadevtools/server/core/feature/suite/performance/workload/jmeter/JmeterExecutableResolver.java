package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Resolves configured or Application-supplied local JMeter executable locations. */
public final class JmeterExecutableResolver {

    private final Supplier<List<String>> discoveredLocations;

    /** Creates a resolver with no ambient discovery locations. */
    public JmeterExecutableResolver() {
        this(List::of);
    }

    /** Creates a resolver from Application-owned discovery locations. */
    public JmeterExecutableResolver(Supplier<List<String>> discoveredLocations) {
        this.discoveredLocations = discoveredLocations;
    }

    /** Finds a usable JMeter executable below the provided installation path. */
    public Optional<String> resolve(String installationPath) {
        Optional<String> configured = resolveLocation(installationPath);
        if (configured.isPresent()) {
            return configured;
        }
        for (String location : discoveredLocations.get()) {
            Optional<String> discovered = resolveLocation(location);
            if (discovered.isPresent()) {
                return discovered;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> resolveLocation(String location) {
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        Path home = Path.of(location.trim());
        if (Files.isRegularFile(home)) {
            return Optional.of(home.toAbsolutePath().toString());
        }
        for (String candidate : candidates()) {
            Path direct = home.resolve(candidate);
            if (Files.isRegularFile(direct)) {
                return Optional.of(direct.toAbsolutePath().toString());
            }
            Path executable = home.resolve("bin").resolve(candidate);
            if (Files.isRegularFile(executable)) {
                return Optional.of(executable.toAbsolutePath().toString());
            }
        }
        return Optional.empty();
    }

    private static String[] candidates() {
        return new String[] {"jmeter.bat", "jmeter.cmd", "jmeter.exe", "jmeter"};
    }
}
