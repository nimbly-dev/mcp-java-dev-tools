package com.nimbly.mcpjavadevtools.agent.failure;

import java.net.URL;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;

/** Parses a Java stack trace into bounded, deterministic Failure Lens facts. */
public final class FailureTraceAnalyzer {
  private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
      "^(?:Caused by:\\s+|Suppressed:\\s+)?([A-Za-z_$][A-Za-z0-9_$.]*)(?::\\s*(.*))?$");
  private static final Pattern UNCAUGHT_THREAD_PREFIX = Pattern.compile(
      "^Exception in thread \\\"[^\\\"]+\\\"\\s+(.+)$");
  private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d{3,}\\b");
  private static final Pattern UUID_PATTERN = Pattern.compile(
      "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
  private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile(
      "\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?\\b");
  private static final int MAX_SECTIONS = 8;
  private static final int MAX_FRAMES_PER_SECTION = 4;

  private FailureTraceAnalyzer() {}

  public static FailureTraceAnalysis analyze(String trace) {
    List<ExceptionSection> sections = parseSections(trace);
    if (sections.isEmpty()) return incomplete("trace_exception_missing");
    return buildAnalysis(sections);
  }

  public static FailureTraceAnalysis analyze(Throwable thrown) {
    if (thrown == null) return incomplete("observed_exception_missing");
    StringWriter buffer = new StringWriter();
    thrown.printStackTrace(new PrintWriter(buffer));
    return analyze(buffer.toString());
  }

  private static List<ExceptionSection> parseSections(String trace) {
    List<ExceptionSection> sections = new ArrayList<>();
    ExceptionSection current = null;
    if (trace == null) return sections;
    for (String line : trace.split("\\R")) {
      ExceptionHeader header = parseExceptionHeader(line);
      if (header != null) {
        current = new ExceptionSection(header.type(), header.message(), header.suppressed());
        sections.add(current);
      } else if (current != null) {
        FailureFrame frame = parseFrame(line);
        if (frame != null) current.frames.add(frame);
        if (line.trim().startsWith("... ")) current.elidedFrames = true;
      }
    }
    return sections;
  }

  private static FailureTraceAnalysis buildAnalysis(List<ExceptionSection> sections) {
    ExceptionSection expected = sections.get(0);
    ExceptionSection root = findRootCause(sections);
    FailureFrame nearestFrame = firstApplicationFrame(sections);
    List<String> incomplete = collectIncompleteness(expected, root, nearestFrame);
    FailureFingerprint fingerprint = new FailureFingerprint(
        expected.type,
        root.type,
        nearestFrame,
        normalizeMessage(expected.message),
        incomplete.isEmpty(),
        incomplete);
    return new FailureTraceAnalysis(
        fingerprint,
        candidates(sections),
        firstDependencyBoundary(sections),
        exceptionSections(sections),
        incomplete);
  }

  private static ExceptionSection findRootCause(List<ExceptionSection> sections) {
    ExceptionSection root = sections.get(0);
    for (ExceptionSection section : sections) {
      if (!section.suppressed) root = section;
    }
    return root;
  }

  private static List<String> collectIncompleteness(
      ExceptionSection expected,
      ExceptionSection root,
      FailureFrame nearestFrame
  ) {
    List<String> reasons = new ArrayList<>();
    if (nearestFrame == null) reasons.add("application_frame_missing");
    if (expected.elidedFrames || root.elidedFrames) reasons.add("elided_frames_present");
    if (nearestFrame != null && nearestFrame.lineNumber() == null) reasons.add("source_line_missing");
    if (nearestFrame != null && nearestFrame.resolutionReason() != null) {
      reasons.add(nearestFrame.resolutionReason());
    }
    return reasons;
  }

  private static List<FailureFrame> candidates(List<ExceptionSection> sections) {
    LinkedHashSet<FailureFrame> candidates = new LinkedHashSet<>();
    for (ExceptionSection section : sections) {
      if (section.suppressed) continue;
      addApplicationFrames(candidates, section.frames, 2);
      if (candidates.size() >= 2) break;
    }
    return List.copyOf(candidates);
  }

  private static List<FailureExceptionSection> exceptionSections(List<ExceptionSection> sections) {
    List<FailureExceptionSection> result = new ArrayList<>();
    for (ExceptionSection section : sections) {
      if (result.size() >= MAX_SECTIONS) break;
      result.add(new FailureExceptionSection(
          section.type,
          section.suppressed,
          section.elidedFrames,
          boundedFrames(section.frames)));
    }
    return List.copyOf(result);
  }

  private static List<FailureFrame> boundedFrames(List<FailureFrame> frames) {
    if (frames.size() <= MAX_FRAMES_PER_SECTION) return List.copyOf(frames);
    return List.copyOf(frames.subList(0, MAX_FRAMES_PER_SECTION));
  }

  private static void addApplicationFrames(
      LinkedHashSet<FailureFrame> candidates,
      List<FailureFrame> frames,
      int limit
  ) {
    for (FailureFrame frame : frames) {
      if (!"application".equals(frame.ownership())) continue;
      candidates.add(frame);
      if (candidates.size() >= limit) return;
    }
  }

  private static FailureTraceAnalysis incomplete(String reason) {
    FailureFingerprint fingerprint = new FailureFingerprint(
        "",
        "",
        null,
        "",
        false,
        List.of(reason));
    return new FailureTraceAnalysis(fingerprint, List.of(), null, List.of(), List.of(reason));
  }

  private static ExceptionHeader parseExceptionHeader(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.startsWith("at ") || trimmed.startsWith("... ")) return null;
    Matcher threadPrefix = UNCAUGHT_THREAD_PREFIX.matcher(trimmed);
    if (threadPrefix.matches()) trimmed = threadPrefix.group(1);
    Matcher matcher = EXCEPTION_PATTERN.matcher(trimmed);
    if (!matcher.matches()) return null;
    String type = matcher.group(1);
    if (!type.contains(".")) return null;
    boolean suppressed = trimmed.startsWith("Suppressed:");
    return new ExceptionHeader(type, matcher.group(2), suppressed);
  }

  private static FailureFrame parseFrame(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (!trimmed.startsWith("at ")) return null;
    int opening = trimmed.indexOf('(');
    int closing = trimmed.lastIndexOf(')');
    if (opening < 4 || closing <= opening) return null;
    String reference = withoutModulePrefix(trimmed.substring(3, opening));
    int methodSeparator = reference.lastIndexOf('.');
    if (methodSeparator <= 0 || methodSeparator == reference.length() - 1) return null;
    String className = reference.substring(0, methodSeparator);
    String methodName = reference.substring(methodSeparator + 1);
    SourceLocation source = sourceLocation(trimmed.substring(opening + 1, closing));
    FrameResolution resolution = resolveFrame(className, methodName);
    return new FailureFrame(
        className,
        methodName,
        source.fileName(),
        source.lineNumber(),
        ownershipFor(className),
        resolution.codeSource(),
        resolution.methodDescriptor(),
        resolution.codeSourceCandidates(),
        resolution.reason());
  }

  private static String withoutModulePrefix(String reference) {
    int separator = reference.lastIndexOf('/');
    return separator < 0 ? reference : reference.substring(separator + 1);
  }

  private static SourceLocation sourceLocation(String location) {
    if (location == null || location.equals("Unknown Source") || location.equals("Native Method")) {
      return new SourceLocation(null, null);
    }
    int separator = location.lastIndexOf(':');
    if (separator < 1 || separator == location.length() - 1) return new SourceLocation(location, null);
    Integer line = parseLineNumber(location.substring(separator + 1));
    return new SourceLocation(location.substring(0, separator), line);
  }

  private static Integer parseLineNumber(String value) {
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static FailureFrame firstApplicationFrame(List<ExceptionSection> sections) {
    for (ExceptionSection section : sections) {
      if (section.suppressed) continue;
      for (FailureFrame frame : section.frames) {
        if ("application".equals(frame.ownership())) return frame;
      }
    }
    return null;
  }

  private static FailureFrame firstDependencyBoundary(List<ExceptionSection> sections) {
    for (ExceptionSection section : sections) {
      for (FailureFrame frame : section.frames) {
        if ("dependency".equals(frame.ownership())) return frame;
      }
    }
    return null;
  }

  private static String ownershipFor(String className) {
    if (ProbeRuntime.isApplicationClass(className)) return "application";
    if (className.startsWith("java.") || className.startsWith("javax.")
        || className.startsWith("jdk.") || className.startsWith("sun.")) return "jdk";
    return "dependency";
  }

  private static FrameResolution resolveFrame(String className, String methodName) {
    List<Class<?>> loadedClasses = ProbeRuntime.loadedClasses(className);
    if (loadedClasses.size() == 1) {
      Class<?> loadedClass = loadedClasses.get(0);
      return resolvedFrame(loadedClass, methodName);
    }
    if (loadedClasses.size() > 1) {
      return new FrameResolution(null, null, codeSourcesFor(loadedClasses), "loaded_class_ambiguous");
    }
    try {
      Class<?> type = Class.forName(className, false, contextClassLoader());
      return resolvedFrame(type, methodName);
    } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
      return new FrameResolution(null, null, List.of(), "class_not_loaded");
    }
  }

  private static FrameResolution resolvedFrame(Class<?> type, String methodName) {
    String codeSource = codeSourceFor(type);
    String descriptor = uniqueMethodDescriptor(type, methodName);
    String reason = descriptor == null ? "method_descriptor_ambiguous" : null;
    return new FrameResolution(
        codeSource,
        descriptor,
        codeSource == null ? List.of() : List.of(codeSource),
        reason);
  }

  private static List<String> codeSourcesFor(List<Class<?>> types) {
    LinkedHashSet<String> sources = new LinkedHashSet<>();
    for (Class<?> type : types) {
      String source = codeSourceFor(type);
      if (source != null) sources.add(source);
    }
    return List.copyOf(sources);
  }

  private static String codeSourceFor(Class<?> type) {
    ProtectionDomain domain = type.getProtectionDomain();
    CodeSource source = domain == null ? null : domain.getCodeSource();
    URL location = source == null ? null : source.getLocation();
    return location == null ? null : location.toExternalForm();
  }

  private static String uniqueMethodDescriptor(Class<?> type, String methodName) {
    List<String> descriptors = new ArrayList<>();
    if ("<init>".equals(methodName)) {
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        descriptors.add(constructorDescriptor(constructor));
      }
    } else {
      for (Method method : type.getDeclaredMethods()) {
        if (methodName.equals(method.getName())) descriptors.add(methodDescriptor(method));
      }
    }
    return descriptors.size() == 1 ? descriptors.get(0) : null;
  }

  private static String constructorDescriptor(Constructor<?> constructor) {
    return parameterDescriptor(constructor.getParameterTypes()) + "V";
  }

  private static String methodDescriptor(Method method) {
    return parameterDescriptor(method.getParameterTypes()) + typeDescriptor(method.getReturnType());
  }

  private static String parameterDescriptor(Class<?>[] parameterTypes) {
    StringBuilder builder = new StringBuilder("(");
    for (Class<?> parameterType : parameterTypes) builder.append(typeDescriptor(parameterType));
    return builder.append(')').toString();
  }

  private static String typeDescriptor(Class<?> type) {
    if (type.isArray()) return type.getName().replace('.', '/');
    if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
    if (type == void.class) return "V";
    if (type == boolean.class) return "Z";
    if (type == byte.class) return "B";
    if (type == char.class) return "C";
    if (type == short.class) return "S";
    if (type == int.class) return "I";
    if (type == long.class) return "J";
    if (type == float.class) return "F";
    return "D";
  }

  private static ClassLoader contextClassLoader() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    return loader == null ? FailureTraceAnalyzer.class.getClassLoader() : loader;
  }

  private static String normalizeMessage(String message) {
    if (message == null || message.isBlank()) return "";
    String normalized = UUID_PATTERN.matcher(message.trim()).replaceAll("{uuid}");
    normalized = ISO_TIMESTAMP_PATTERN.matcher(normalized).replaceAll("{timestamp}");
    return NUMBER_PATTERN.matcher(normalized).replaceAll("{number}");
  }

  private record ExceptionHeader(String type, String message, boolean suppressed) {}

  private record FrameResolution(
      String codeSource,
      String methodDescriptor,
      List<String> codeSourceCandidates,
      String reason
  ) {}

  private record SourceLocation(String fileName, Integer lineNumber) {}

  private static final class ExceptionSection {
    private final String type;
    private final String message;
    private final boolean suppressed;
    private final List<FailureFrame> frames = new ArrayList<>();
    private boolean elidedFrames;

    private ExceptionSection(String type, String message, boolean suppressed) {
      this.type = type;
      this.message = message;
      this.suppressed = suppressed;
    }
  }
}
