package com.nimbly.mcpjavadevtools.agent.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

final class ExecutionPathCollector {
  private static final int CAPTURE_EXECUTION_PATH_MAX_FRAMES = 12;
  private static final String[] EXECUTION_PATH_EXCLUDED_PREFIXES = new String[] {
      "com.nimbly.mcpjavadevtools.agent.",
      "java.",
      "javax.",
      "jdk.",
      "sun.",
      "net.bytebuddy.",
      "org.apache.",
      "org.springframework."
  };

  private static volatile List<Pattern> includeRegex = Collections.emptyList();
  private static volatile List<Pattern> excludeRegex = Collections.emptyList();

  private ExecutionPathCollector() {}

  static void configureScope(List<String> includePatterns, List<String> excludePatterns) {
    includeRegex = compileGlobPatterns(includePatterns);
    excludeRegex = compileGlobPatterns(excludePatterns);
  }

  static List<String> collectExecutionPaths(String dottedClassName, String methodName) {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    if (stack == null || stack.length == 0) {
      return Collections.singletonList(
          formatExecutionFrame(dottedClassName, methodName, -1)
      );
    }

    List<StackTraceElement> frames = new ArrayList<>();
    int fallbackLine = -1;
    for (StackTraceElement frame : stack) {
      if (frame == null) continue;
      String className = frame.getClassName();
      if (className == null || className.isBlank()) continue;
      if (fallbackLine <= 0
          && dottedClassName.equals(className)
          && methodName.equals(frame.getMethodName())
          && frame.getLineNumber() > 0) {
        fallbackLine = frame.getLineNumber();
      }
      if (isExecutionPathExcludedClass(className)) continue;
      frames.add(frame);
    }

    Collections.reverse(frames);
    List<String> out = new ArrayList<>();
    String previous = null;
    for (StackTraceElement frame : frames) {
      String normalizedClassName = normalizeExecutionClassName(frame.getClassName(), dottedClassName);
      int renderedLine = frame.getLineNumber();
      if (renderedLine <= 0
          && dottedClassName.equals(normalizedClassName)
          && methodName.equals(frame.getMethodName())
          && fallbackLine > 0) {
        renderedLine = fallbackLine;
      }
      String rendered = formatExecutionFrame(normalizedClassName, frame.getMethodName(), renderedLine);
      if (rendered.equals(previous)) continue;
      out.add(rendered);
      previous = rendered;
    }

    if (out.size() > CAPTURE_EXECUTION_PATH_MAX_FRAMES) {
      out = new ArrayList<>(out.subList(out.size() - CAPTURE_EXECUTION_PATH_MAX_FRAMES, out.size()));
    }
    if (out.isEmpty()) {
      out.add(formatExecutionFrame(dottedClassName, methodName, fallbackLine));
    }
    return out;
  }

  private static boolean isExecutionPathExcludedClass(String className) {
    for (String prefix : EXECUTION_PATH_EXCLUDED_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    List<Pattern> include = includeRegex;
    if (!include.isEmpty() && !matchesAny(include, className)) {
      return true;
    }
    List<Pattern> exclude = excludeRegex;
    if (!exclude.isEmpty() && matchesAny(exclude, className)) {
      return true;
    }
    return false;
  }

  private static boolean matchesAny(List<Pattern> patterns, String value) {
    for (Pattern pattern : patterns) {
      if (pattern.matcher(value).matches()) return true;
    }
    return false;
  }

  private static List<Pattern> compileGlobPatterns(List<String> patterns) {
    if (patterns == null || patterns.isEmpty()) return Collections.emptyList();
    List<Pattern> out = new ArrayList<>();
    for (String raw : patterns) {
      String pattern = raw == null ? "" : raw.trim();
      if (pattern.isEmpty()) continue;
      out.add(Pattern.compile(globToRegex(pattern)));
    }
    return out;
  }

  private static String globToRegex(String globOrPrefix) {
    boolean hasWildcard = globOrPrefix.indexOf('*') >= 0;
    String normalized =
        hasWildcard
            ? globOrPrefix
            : globOrPrefix.endsWith(".") ? globOrPrefix + "**" : globOrPrefix + ".**";

    StringBuilder out = new StringBuilder("^");
    int idx = 0;
    while (idx < normalized.length()) {
      char ch = normalized.charAt(idx);
      if (ch == '*') {
        if (idx + 1 < normalized.length() && normalized.charAt(idx + 1) == '*') {
          out.append(".*");
          idx += 2;
        } else {
          out.append("[^.]*");
          idx++;
        }
      } else {
        out.append(Pattern.quote(String.valueOf(ch)));
        idx++;
      }
    }
    out.append("$");
    return out.toString();
  }

  private static String normalizeExecutionClassName(String className, String targetClassName) {
    if (className == null || className.isBlank()) return "UnknownClass";
    if (targetClassName == null || targetClassName.isBlank()) return className;
    if (className.startsWith(targetClassName + "$$")) {
      return targetClassName;
    }
    return className;
  }

  private static String formatExecutionFrame(
      String className,
      String methodName,
      int lineNumber
  ) {
    String cls = (className == null || className.isBlank()) ? "UnknownClass" : className;
    String method = (methodName == null || methodName.isBlank()) ? "<unknown>" : methodName;
    String line = lineNumber > 0 ? String.valueOf(lineNumber) : "?";
    int lastDot = cls.lastIndexOf('.');
    String simpleClass = lastDot >= 0 ? cls.substring(lastDot + 1) : cls;
    return simpleClass + "." + method + "()#" + line;
  }
}

