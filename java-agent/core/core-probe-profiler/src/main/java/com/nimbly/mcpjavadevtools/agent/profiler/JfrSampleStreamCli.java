package com.nimbly.mcpjavadevtools.agent.profiler;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class JfrSampleStreamCli {
  private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
      "jdk.ExecutionSample",
      "jdk.NativeMethodSample",
      "profiler.WallClockSample"
  );
  private static final int MAX_STACK_DEPTH = 64;

  private JfrSampleStreamCli() {
  }

  public static void main(String[] args) throws Exception {
    try (
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true)
    ) {
      int exitCode = run(args, out, err);
      out.flush();
      err.flush();
      if (exitCode != 0) {
        System.exit(exitCode);
      }
    }
  }

  static int run(String[] args, Writer out, PrintWriter err) {
    if (args.length != 1) {
      err.println("usage: JfrSampleStreamCli <jfrPath>");
      return 2;
    }

    Path jfrPath = Path.of(args[0]).toAbsolutePath().normalize();
    if (!Files.isRegularFile(jfrPath)) {
      err.println("jfr_file_missing:" + jfrPath);
      return 2;
    }

    try (RecordingFile recordingFile = new RecordingFile(jfrPath)) {
      while (recordingFile.hasMoreEvents()) {
        RecordedEvent event = recordingFile.readEvent();
        String eventType = event.getEventType().getName();
        if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
          continue;
        }
        String eventLine = toJsonLine(eventType, resolveSampleWeight(event), event.getStackTrace());
        if (eventLine == null) {
          continue;
        }
        out.write(eventLine);
        out.write('\n');
      }
      return 0;
    } catch (IOException ex) {
      err.println("jfr_read_failed:" + sanitizeDetail(ex.getMessage()));
      return 1;
    } catch (RuntimeException ex) {
      err.println("jfr_stream_failed:" + sanitizeDetail(ex.getMessage()));
      return 1;
    }
  }

  private static String toJsonLine(String eventType, long sampleWeight, RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return null;
    }
    List<RecordedFrame> frames = stackTrace.getFrames();
    if (frames == null || frames.isEmpty()) {
      return null;
    }

    StringBuilder line = new StringBuilder(512);
    line.append("{\"type\":\"").append(escapeJson(eventType)).append("\",");
    line.append("\"samples\":").append(Math.max(1L, sampleWeight)).append(",");
    line.append("\"frames\":[");

    boolean wroteFrame = false;
    int frameLimit = Math.min(frames.size(), MAX_STACK_DEPTH);
    for (int index = 0; index < frameLimit; index += 1) {
      String methodRef = buildMethodRef(frames.get(index));
      if (methodRef == null) {
        continue;
      }
      if (wroteFrame) {
        line.append(',');
      }
      line.append('"').append(escapeJson(methodRef)).append('"');
      wroteFrame = true;
    }

    if (!wroteFrame) {
      return null;
    }

    line.append("]}");
    return line.toString();
  }

  private static long resolveSampleWeight(RecordedEvent event) {
    try {
      if (event.hasField("samples")) {
        long value = event.getLong("samples");
        if (value > 0L) {
          return value;
        }
      }
    } catch (RuntimeException ignored) {
      return 1L;
    }
    return 1L;
  }

  private static String buildMethodRef(RecordedFrame frame) {
    if (frame == null) {
      return null;
    }
    RecordedMethod method = frame.getMethod();
    if (method == null || method.getType() == null) {
      return null;
    }
    String typeName = method.getType().getName();
    String methodName = method.getName();
    if (typeName == null || typeName.isBlank() || methodName == null || methodName.isBlank()) {
      return null;
    }
    return typeName.trim() + "#" + methodName.trim();
  }

  private static String escapeJson(String raw) {
    StringBuilder escaped = new StringBuilder(raw.length() + 16);
    for (int index = 0; index < raw.length(); index += 1) {
      char current = raw.charAt(index);
      switch (current) {
        case '\\':
          escaped.append("\\\\");
          break;
        case '"':
          escaped.append("\\\"");
          break;
        case '\b':
          escaped.append("\\b");
          break;
        case '\f':
          escaped.append("\\f");
          break;
        case '\n':
          escaped.append("\\n");
          break;
        case '\r':
          escaped.append("\\r");
          break;
        case '\t':
          escaped.append("\\t");
          break;
        default:
          if (current < 0x20) {
            escaped.append(String.format("\\u%04x", (int) current));
          } else {
            escaped.append(current);
          }
          break;
      }
    }
    return escaped.toString();
  }

  private static String sanitizeDetail(String detail) {
    if (detail == null || detail.isBlank()) {
      return "unknown";
    }
    return detail.trim().replace('\n', ' ').replace('\r', ' ');
  }
}
