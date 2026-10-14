package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis.PerformanceAnalysisOptions;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis.PerformanceStackSamples;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Reads the bounded JFR sample/dictionary contract; every instance belongs to one capture. */
class PerformanceJfrSamplingReader {
    private static final int MAX_EVENTS = 100_000;
    private final PerformanceAnalysisOptions options;
    private final String sessionId;
    private final Set<String> sourceTypes = new LinkedHashSet<>();
    private final Map<String, Object> sampling = new LinkedHashMap<>();
    private final Map<Long, List<String>> stacks = new LinkedHashMap<>();
    private int encodedStackBytes;
    private int decodedStackBytes;

    PerformanceJfrSamplingReader(PerformanceAnalysisOptions options, String sessionId) {
        this.options = options;
        this.sessionId = sessionId;
    }

    Map<List<String>, PerformanceStackSamples> read(Path path) throws IOException {
        readDictionary(path);
        Map<List<String>, PerformanceStackSamples> samples = new LinkedHashMap<>();
        try (RecordingFile recording = new RecordingFile(path)) {
            int events = 0;
            long weightedSamples = 0;
            while (recording.hasMoreEvents()) {
                if (++events > MAX_EVENTS) {
                    throw new IOException("performance_jfr_sample_limit_exceeded");
                }
                weightedSamples += recordSample(recording.readEvent(), samples);
                if (weightedSamples > MAX_EVENTS) {
                    throw new IOException("performance_jfr_sample_limit_exceeded");
                }
            }
        }
        if (sourceTypes.contains("mcpjvm.WallClockSample") && !Boolean.TRUE.equals(sampling.get("complete"))) {
            throw new IOException("performance_wall_sampling_incomplete");
        }
        return samples;
    }

    List<String> sourceTypes() {
        return List.copyOf(sourceTypes);
    }

    Map<String, Object> sampling() {
        return Map.copyOf(sampling);
    }

    private int recordSample(RecordedEvent event, Map<List<String>, PerformanceStackSamples> samples) throws IOException {
        String type = event.getEventType().getName();
        if (type.startsWith("mcpjvm.WallClock") && !sessionId.equals(event.getString("sessionId"))) {
            return 0;
        }
        if ("mcpjvm.WallClockStack".equals(type) || "mcpjvm.WallClockSamplingStatus".equals(type)) {
            return 0;
        }
        if (!sampleType(type)) {
            return 0;
        }
        List<String> frames = frames(event);
        if (frames.isEmpty()) {
            return 0;
        }
        sourceTypes.add(type);
        int weight = sampleWeight(event);
        samples.merge(List.copyOf(frames), new PerformanceStackSamples(weight, sampleTimeMs(event, weight)), PerformanceStackSamples::plus);
        return weight;
    }

    private void readDictionary(Path path) throws IOException {
        // JFR buffers from different threads/chunks need not place definitions before their references.
        try (RecordingFile recording = new RecordingFile(path)) {
            int events = 0;
            while (recording.hasMoreEvents()) {
                if (++events > MAX_EVENTS) {
                    throw new IOException("performance_jfr_sample_limit_exceeded");
                }
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!type.startsWith("mcpjvm.WallClock") || !sessionId.equals(event.getString("sessionId"))) {
                    continue;
                }
                if ("mcpjvm.WallClockStack".equals(type)) {
                    recordStack(event);
                } else if ("mcpjvm.WallClockSamplingStatus".equals(type)) {
                    recordStatus(event);
                }
            }
        }
    }

    private void recordStack(RecordedEvent event) throws IOException {
        String encoded = event.getString("encodedFrames");
        encodedStackBytes += encoded.length();
        if (encodedStackBytes > 512_000) {
            throw new IOException("performance_wall_stack_invalid");
        }
        List<String> frames = decodedStack(encoded);
        long id = event.getLong("stackId");
        if (id <= 0 || frames.isEmpty() || frames.size() > 64 || frames.stream().anyMatch(frame -> !frame.contains("#"))) {
            throw new IOException("performance_wall_stack_invalid");
        }
        List<String> previous = stacks.putIfAbsent(id, frames);
        if (previous != null && !previous.equals(frames)) {
            throw new IOException("performance_wall_stack_conflict");
        }
    }

    private List<String> decodedStack(String encoded) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            byte[] decoded = gzip.readNBytes(16_385);
            decodedStackBytes += decoded.length;
            if (decoded.length > 16_384 || decodedStackBytes > 4_194_304) {
                throw new IOException("performance_wall_stack_invalid");
            }
            return Arrays.stream(new String(decoded, StandardCharsets.UTF_8).split("\n")).toList();
        } catch (IllegalArgumentException exception) {
            throw new IOException("performance_wall_stack_invalid", exception);
        }
    }

    private void recordStatus(RecordedEvent event) throws IOException {
        if (!event.getBoolean("complete")) {
            throw new IOException("performance_wall_sampling_incomplete");
        }
        sampling.put("complete", true);
        sampling.put("intervalNanos", event.getLong("intervalNanos"));
        sampling.put("availableProcessors", event.getInt("availableProcessors"));
        sampling.put("initialDelayMs", event.hasField("initialDelayNanos") ? event.getLong("initialDelayNanos") / 1_000_000.0 : 0.0);
        sampling.put("threadScope", "platform_threads");
    }

    private boolean sampleType(String type) {
        if ("wall".equals(options.event())) {
            return List.of("mcpjvm.WallClockSample", "profiler.ExecutionSample", "profiler.WallClockSample").contains(type);
        }
        return List.of("jdk.ExecutionSample", "jdk.NativeMethodSample", "profiler.ExecutionSample").contains(type);
    }

    private double sampleTimeMs(RecordedEvent event, int weight) throws IOException {
        if ("mcpjvm.WallClockSample".equals(event.getEventType().getName())) {
            long duration = event.getLong("sampleDurationNanos");
            if (duration <= 0 || duration > 5_000_000_000L) {
                throw new IOException("performance_wall_sample_invalid");
            }
            return duration / 1_000_000.0;
        }
        long interval = options.intervalNanos() == null ? 10_000_000L : options.intervalNanos();
        return weight * (interval / 1_000_000.0);
    }

    private List<String> frames(RecordedEvent event) throws IOException {
        if ("mcpjvm.WallClockSample".equals(event.getEventType().getName())) {
            List<String> frames = stacks.get(event.getLong("stackId"));
            if (frames == null) {
                throw new IOException("performance_wall_stack_missing");
            }
            return frames;
        }
        RecordedStackTrace stack = event.getStackTrace();
        List<String> frames = new ArrayList<>();
        if (stack == null) {
            return frames;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            RecordedMethod method = frame.getMethod();
            if (method != null) {
                String reference = method.getType().getName() + "#" + method.getName();
                if (frames.isEmpty() || !frames.getLast().equals(reference)) {
                    frames.add(reference);
                }
            }
        }
        return frames;
    }

    private static int sampleWeight(RecordedEvent event) {
        if (!event.hasField("samples")) {
            return 1;
        }
        long count = ((Number) event.getValue("samples")).longValue();
        return (int) Math.max(1, Math.min(count, MAX_EVENTS));
    }
}
