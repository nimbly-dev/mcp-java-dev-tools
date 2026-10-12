package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** JSON-RPC STDIO process driver for packaged MCP acceptance tests. */
class McpServerProcess implements AutoCloseable {

    private final Process process;
    private final OutputStream stdin;
    private final BlockingQueue<String> stdoutQueue = new LinkedBlockingQueue<>();
    private final List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
    private final Set<Integer> responseIds = ConcurrentHashMap.newKeySet();
    private final StringBuilder stderr = new StringBuilder();
    private final Consumer<String> stderrObserver;
    private final ExecutorService readers = Executors.newFixedThreadPool(2);
    private volatile List<String> roots;
    private final Map<Integer, JsonNode> pendingResponses = new ConcurrentHashMap<>();
    private volatile int rootsRequests;
    private volatile boolean rootsUnavailable;
    private boolean closed;

    private McpServerProcess(Process process, Path workspaceRoot, Consumer<String> stderrObserver) {
        this.process = process;
        this.stderrObserver = stderrObserver;
        this.stdin = process.getOutputStream();
        this.roots = List.of(workspaceRoot.toUri().toString());
        JavaItScenario.own(process);
        readers.submit(() -> collectStdout(process.getInputStream()));
        readers.submit(() -> collectStderr(process.getErrorStream()));
    }

    static McpServerProcess start(Path jar, Path workspaceRoot, String... applicationArguments)
            throws IOException {
        return startWithEnvironment(jar, workspaceRoot, Map.of(), applicationArguments);
    }

    static McpServerProcess startWithEnvironment(Path jar, Path workspaceRoot,
            Map<String, String> environment, String... applicationArguments) throws IOException {
        return startWithStderrObserver(jar, workspaceRoot, environment, line -> { }, applicationArguments);
    }

    static McpServerProcess startWithStderrObserver(Path jar, Path workspaceRoot,
            Map<String, String> environment, Consumer<String> stderrObserver, String... applicationArguments) throws IOException {
        assertThat(jar).as("Executable application JAR prerequisite").isRegularFile();
        Files.createDirectories(workspaceRoot);
        List<String> command = new ArrayList<>(List.of(javaBinary(), "-jar", jar.toAbsolutePath().toString()));
        command.addAll(List.of(applicationArguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspaceRoot.toFile())
                .redirectErrorStream(false);
        builder.environment().keySet().removeIf(key -> key.startsWith("MCP_") || key.startsWith("MCPJVM_")
                || key.startsWith("SPRING_") || Set.of("INIT_CWD", "PWD", "JAVA_TOOL_OPTIONS",
                        "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key));
        builder.environment().putAll(environment);
        Process process = builder.start();
        return new McpServerProcess(process, workspaceRoot, stderrObserver);
    }

    synchronized void send(Map<String, Object> message) throws IOException {
        try {
            stdin.write(McpServerStdioSupport.JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException failure) {
            throw new IOException(
                    "Could not write to the MCP server process (alive=" + process.isAlive()
                            + ", stderr=" + stderrText() + ").",
                    failure);
        }
    }

    synchronized void send(JsonNode message) throws IOException {
        stdin.write(McpServerStdioSupport.JSON.writeValueAsBytes(message));
        stdin.write('\n');
        stdin.flush();
    }

    JsonNode responseFor(int id) throws Exception {
        return responseFor(id, McpServerStdioSupport.RESPONSE_TIMEOUT);
    }

    JsonNode responseFor(int id, Duration timeout) throws Exception {
        JsonNode pending = pendingResponses.remove(id);
        if (pending != null) {
            return pending;
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            String line = stdoutQueue.poll(100, TimeUnit.MILLISECONDS);
            if (line == null) {
                if (!process.isAlive()) {
                    throw new AssertionError("MCP process exited before response " + id + ". stderr=" + stderrText());
                }
                continue;
            }
            JsonNode message = McpServerStdioSupport.JSON.readTree(line);
            if (message.has("id") && !message.has("method")) {
                responseIds.add(message.path("id").asInt());
            }
            if (message.path("id").asInt(-1) == id) {
                return message;
            }
            if (message.has("id") && !message.has("method")) {
                pendingResponses.put(message.path("id").asInt(), message);
            }
        }
        throw new AssertionError("Timed out waiting for JSON-RPC response " + id + ". stderr=" + stderrText());
    }

    boolean hasResponseFor(int id) {
        return responseIds.contains(id);
    }

    long pid() {
        return process.pid();
    }

    long processStartEpochMs() {
        return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
    }

    int exitCode() {
        return process.exitValue();
    }

    private void respondToRootsRequest(JsonNode request) throws IOException {
        rootsRequests++;
        ObjectNode response = McpServerStdioSupport.JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        if (rootsUnavailable) {
            response.putObject("error").put("code", -32603).put("message", "Roots unavailable");
            send(response);
            return;
        }
        var array = response.putObject("result").putArray("roots");
        for (String root : roots) {
            array.addObject().put("uri", root).put("name", "java-it-workspace");
        }
        send(response);
    }

    void roots(List<Path> values) {
        roots = values.stream().map(path -> path.toUri().toString()).toList();
    }

    void rootUris(List<String> values) {
        roots = List.copyOf(values);
    }

    void rootsUnavailable() {
        rootsUnavailable = true;
    }

    int rootsRequests() {
        return rootsRequests;
    }

    boolean isAlive() {
        return process.isAlive();
    }

    List<String> stdoutLines() {
        synchronized (stdoutLines) {
            return List.copyOf(stdoutLines);
        }
    }

    String stderrText() {
        synchronized (stderr) {
            return stderr.toString();
        }
    }

    void closeInputAndAwaitTermination() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        JavaItScenario.ownDescendants(process);
        stdin.close();
        if (!process.waitFor(McpServerStdioSupport.SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            JavaItScenario.stopTree(process.toHandle());
            fail("MCP process did not terminate after stdin closed within "
                    + McpServerStdioSupport.SHUTDOWN_TIMEOUT);
        }
        awaitReaders();
        assertThat(process.exitValue()).isZero();
    }

    void awaitStartupFailure() throws Exception {
        if (!process.waitFor(McpServerStdioSupport.SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            fail("MCP process did not terminate after startup failure within "
                    + McpServerStdioSupport.SHUTDOWN_TIMEOUT);
        }
        closed = true;
        awaitReaders();
        assertThat(process.exitValue()).isNotZero();
    }

    void sendPosixSignal(String signal) throws Exception {
        Process signalProcess = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
                .redirectErrorStream(true)
                .start();
        assertThat(signalProcess.waitFor(
                McpServerStdioSupport.RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(signalProcess.exitValue()).isZero();
        awaitSignalTermination(signal);
    }

    @Override
    public void close() throws Exception {
        try {
            closeInputAndAwaitTermination();
        } finally {
            awaitReaders();
        }
    }

    private void awaitReaders() throws InterruptedException {
        readers.shutdown();
        if (!readers.awaitTermination(
                McpServerStdioSupport.RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            readers.shutdownNow();
            readers.awaitTermination(2, TimeUnit.SECONDS);
            fail("MCP output readers did not drain within " + McpServerStdioSupport.RESPONSE_TIMEOUT);
        }
    }

    private void awaitSignalTermination(String signal) throws Exception {
        if (!process.waitFor(McpServerStdioSupport.SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            fail("MCP process did not terminate after SIG" + signal + " within "
                    + McpServerStdioSupport.SHUTDOWN_TIMEOUT);
        }
        closed = true;
        awaitReaders();
    }

    private void collectStdout(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    stdoutLines.add(line);
                    routeServerMessage(line);
                    recordResponseId(line);
                }
            }
        } catch (IOException exception) {
            appendStderr("stdout reader error: " + exception.getMessage());
        }
    }

    private void routeServerMessage(String line) {
        try {
            JsonNode message = McpServerStdioSupport.JSON.readTree(line);
            if ("roots/list".equals(message.path("method").asText())) {
                respondToRootsRequest(message);
                return;
            }
        } catch (IOException failure) {
            // Preserve malformed protocol output for response/purity assertions.
        }
        stdoutQueue.offer(line);
    }

    private void recordResponseId(String line) {
        try {
            JsonNode message = McpServerStdioSupport.JSON.readTree(line);
            if (message.has("id") && !message.has("method")) {
                responseIds.add(message.path("id").asInt());
            }
        } catch (IOException ignored) {
            // Protocol purity is asserted by the owning integration test after the readers drain.
        }
    }

    private void collectStderr(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderrObserver.accept(line);
                appendStderr(line);
            }
        } catch (IOException exception) {
            appendStderr("stderr reader error: " + exception.getMessage());
        }
    }

    private void appendStderr(String line) {
        synchronized (stderr) {
            if (stderr.length() < 16_384) {
                String safe = line.replaceAll("(?i)(bearer\\s+|password=|token=|secret=)[^\\s]+", "$1[redacted]");
                stderr.append(safe, 0, Math.min(safe.length(), 16_384 - stderr.length())).append('\n');
            }
        }
    }

    static String javaBinary() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String executable = windows ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
