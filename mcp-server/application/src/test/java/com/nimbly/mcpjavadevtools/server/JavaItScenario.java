package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.function.Executable;

/** Repository-local state and last-resort resource cleanup for the existing process ITs. */
public class JavaItScenario implements BeforeEachCallback, AfterEachCallback {
    private static final AtomicInteger NEXT_SCENARIO = new AtomicInteger();
    private static Path runDirectory;
    private static final ThreadLocal<JavaItScenario> CURRENT = new ThreadLocal<>();
    private final List<ProcessHandle> processes = new ArrayList<>();
    private Path directory;
    private final AtomicInteger nextWorkspace = new AtomicInteger();

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        JavaItScenario scenario = new JavaItScenario();
        scenario.directory = Files.createDirectory(runDirectory()
                .resolve("s-" + NEXT_SCENARIO.incrementAndGet()));
        context.getStore(ExtensionContext.Namespace.create(JavaItScenario.class)).put("scenario", scenario);
        CURRENT.set(scenario);
    }

    static Path workspace() throws IOException {
        JavaItScenario scenario = CURRENT.get();
        if (scenario == null) {
            throw new IllegalStateException("Process ITs require the JavaItScenario extension");
        }
        return Files.createDirectory(scenario.directory.resolve("w-" + scenario.nextWorkspace.incrementAndGet()));
    }

    private static synchronized Path runDirectory() throws IOException {
        if (runDirectory == null) {
            Path temporary = Files.createDirectories(McpServerStdioSupport.repositoryRoot().resolve("test/.tmp"));
            runDirectory = Files.createTempDirectory(temporary, "java-it-");
        }
        return runDirectory;
    }

    static void own(Process process) {
        own(process.toHandle());
    }

    static void own(ProcessHandle process) {
        CURRENT.get().processes.add(process);
    }

    static void ownDescendants(Process process) {
        CURRENT.get().processes.addAll(process.descendants().toList());
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        JavaItScenario scenario = context.getStore(ExtensionContext.Namespace.create(JavaItScenario.class))
                .get("scenario", JavaItScenario.class);
        scenario.finish(context);
    }

    private void finish(ExtensionContext context) throws Exception {
        boolean failed = context.getExecutionException().isPresent();
        boolean cleanupSucceeded = false;
        try {
            stopOwnedProcesses(processes);
            assertThat(processes).allSatisfy(process -> assertThat(process.isAlive()).isFalse());
            if (!Boolean.getBoolean("mcpItRetainFiles")) {
                try (var children = Files.list(directory)) {
                    for (Path child : children.filter(Files::isDirectory).toList()) {
                        McpServerStdioSupport.deleteRecursively(child);
                    }
                }
            }
            cleanupSucceeded = true;
        } finally {
            try {
                writeEvidence(context, failed, cleanupSucceeded);
            } finally {
                CURRENT.remove();
            }
        }
    }

    private void writeEvidence(ExtensionContext context, boolean failed, boolean cleaned) throws IOException {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scenarioId", context.getRequiredTestClass().getSimpleName()
                + "." + context.getRequiredTestMethod().getName());
        evidence.put("outcome", failed || !cleaned ? "failed" : "passed");
        evidence.put("cleanupSucceeded", cleaned);
        evidence.put("filesRetained", Boolean.getBoolean("mcpItRetainFiles"));
        evidence.put("ownedPids", processes.stream().map(ProcessHandle::pid).toList());
        evidence.put("javaVersion", System.getProperty("java.version"));
        evidence.put("os", System.getProperty("os.name"));
        evidence.put("time", Instant.now().toString());
        evidence.put("tags", context.getTags());
        // Raw MCP responses, command lines, environment and exception text are deliberately excluded.
        McpServerStdioSupport.JSON.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("evidence.json").toFile(), evidence);
        System.err.println("Java IT evidence: " + directory.resolve("evidence.json"));
    }

    static void stopTree(ProcessHandle process) throws Exception {
        List<ProcessHandle> owned = new ArrayList<>(process.descendants().toList());
        owned.add(process);
        assertAll("Stop every process in the owned tree", owned.reversed().stream()
                .map(handle -> (Executable) () -> stopProcess(handle)));
        assertThat(owned).allSatisfy(handle -> assertThat(handle.isAlive()).isFalse());
    }

    static void stopOwnedProcesses(List<ProcessHandle> processes) {
        assertAll("Attempt cleanup of every owned process", processes.stream()
                .map(process -> (Executable) () -> stopTree(process)));
    }

    private static void stopProcess(ProcessHandle handle) throws Exception {
        if (!handle.isAlive()) {
            return;
        }
        handle.destroy();
        try {
            handle.onExit().get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            handle.destroyForcibly();
            handle.onExit().get(5, TimeUnit.SECONDS);
        }
    }
}
