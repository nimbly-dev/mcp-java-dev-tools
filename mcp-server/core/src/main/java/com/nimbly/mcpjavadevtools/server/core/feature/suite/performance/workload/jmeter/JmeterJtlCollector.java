package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects bounded metrics from the CSV JTL shape produced by this Suite. */
public final class JmeterJtlCollector {

    /** Reads JTL metrics or returns an empty result when the Artifact is incomplete. */
    public Metrics collect(Path jtlPath) throws IOException {
        List<String> lines = Files.readAllLines(jtlPath);
        if (lines.size() < 2) {
            return Metrics.empty();
        }
        String[] headers = parse(lines.getFirst());
        Map<String, Integer> indexes = indexes(headers);
        if (!indexes.containsKey("elapsed") || !indexes.containsKey("success")) {
            return Metrics.empty();
        }
        return readRows(lines, indexes);
    }

    private Metrics readRows(List<String> lines, Map<String, Integer> indexes) {
        List<Long> latencies = new ArrayList<>();
        int failed = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] row = parse(line);
            Long elapsed = elapsed(row, indexes.get("elapsed"));
            if (elapsed == null) {
                continue;
            }
            latencies.add(elapsed);
            if (!"true".equalsIgnoreCase(value(row, indexes.get("success")))) {
                failed++;
            }
        }
        return latencies.isEmpty() ? Metrics.empty() : new Metrics(latencies.size(), failed, List.copyOf(latencies));
    }

    private static Map<String, Integer> indexes(String[] headers) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < headers.length; index++) {
            indexes.put(headers[index].trim(), index);
        }
        return indexes;
    }

    private static Long elapsed(String[] row, int index) {
        try {
            long value = Long.parseLong(value(row, index));
            return value >= 0 ? Math.max(1L, value) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String value(String[] row, int index) {
        return index >= 0 && index < row.length ? row[index].trim() : "";
    }

    private static String[] parse(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"' && quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                current.append(character);
                index++;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString());
        return values.toArray(String[]::new);
    }

    /** Immutable JTL metrics representation. */
    public record Metrics(int totalRequests, int failedRequests, List<Long> latenciesMs) {

        /** Provides the deterministic no-metrics result. */
        public static Metrics empty() {
            return new Metrics(0, 0, List.of());
        }
    }
}
