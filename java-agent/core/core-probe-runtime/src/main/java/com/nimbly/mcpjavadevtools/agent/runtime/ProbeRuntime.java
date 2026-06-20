package com.nimbly.mcpjavadevtools.agent.runtime;
import com.nimbly.mcpjavadevtools.agent.runtime.model.ActuationState;
import com.nimbly.mcpjavadevtools.agent.runtime.model.KeyStatus;
import com.nimbly.mcpjavadevtools.agent.runtime.model.RuntimeState;
import com.nimbly.mcpjavadevtools.agent.runtime.model.SessionActuation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ProbeRuntime {
  private static final ConcurrentHashMap<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, AtomicLong> LAST_HIT_EPOCH_MS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, LineTable> RESOLVABLE_LINES_BY_METHOD =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, SessionActuation> SESSION_ACTUATIONS =
      new ConcurrentHashMap<>();

  private static final long MIN_TTL_MS = 1_000L;
  private static final long MAX_TTL_MS = 300_000L;

  private ProbeRuntime() {}

  public static void configure(
      String mode,
      String actuatorId,
      String actuateTargetKey,
      boolean actuateReturnBoolean
  ) {
    // Pre-1.0 breaking behavior: runtime-wide actuation is retired.
    // Startup configuration only resets to safe observe semantics.
    SESSION_ACTUATIONS.clear();
  }

  public static ActuationState armSession(
      String sessionId,
      String actuatorId,
      String targetKey,
      boolean returnBoolean,
      long ttlMs
  ) {
    long now = System.currentTimeMillis();
    long boundedTtlMs = clampTtlMs(ttlMs);
    SessionActuation sessionActuation = new SessionActuation(
        sanitize(sessionId),
        sanitize(actuatorId),
        sanitize(targetKey),
        returnBoolean,
        now + boundedTtlMs
    );
    SESSION_ACTUATIONS.put(sessionActuation.sessionId(), sessionActuation);
    return sessionState(sessionActuation.sessionId(), now);
  }

  public static ActuationState disarmSession(String sessionId) {
    long now = System.currentTimeMillis();
    String normalizedSessionId = sanitize(sessionId);
    SESSION_ACTUATIONS.remove(normalizedSessionId);
    return new ActuationState(
        "observe",
        normalizedSessionId,
        "",
        "",
        null,
        null,
        "disarmed",
        activeSessionCount(now)
    );
  }

  public static ActuationState sessionState(String sessionId) {
    return sessionState(sessionId, System.currentTimeMillis());
  }

  private static ActuationState sessionState(String sessionId, long now) {
    String normalizedSessionId = sanitize(sessionId);
    pruneExpiredSessions(now);
    SessionActuation session = SESSION_ACTUATIONS.get(normalizedSessionId);
    if (session == null) {
      return new ActuationState(
          "observe",
          normalizedSessionId,
          "",
          "",
          null,
          null,
          "disarmed",
          activeSessionCount(now)
      );
    }
    if (session.isExpired(now)) {
      SESSION_ACTUATIONS.remove(normalizedSessionId, session);
      return new ActuationState(
          "observe",
          normalizedSessionId,
          session.actuatorId(),
          session.targetKey(),
          session.returnBoolean(),
          session.expiresAtEpoch(),
          "expired",
          activeSessionCount(now)
      );
    }
    return new ActuationState(
        "actuate",
        session.sessionId(),
        session.actuatorId(),
        session.targetKey(),
        session.returnBoolean(),
        session.expiresAtEpoch(),
        "armed",
        activeSessionCount(now)
    );
  }

  public static void hit(String key) {
    if (key == null || key.isEmpty()) return;
    COUNTS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    LAST_HIT_EPOCH_MS.computeIfAbsent(key, k -> new AtomicLong()).set(System.currentTimeMillis());
  }

  public static void hitLineByClassMethod(String dottedClassName, String methodName, int lineNumber) {
    if (dottedClassName == null || methodName == null) return;
    if (lineNumber <= 0) return;
    hit(dottedClassName + "#" + methodName + ":" + lineNumber);
  }

  public static void registerResolvableLine(
      String dottedClassName,
      String methodName,
      int lineNumber
  ) {
    if (dottedClassName == null || dottedClassName.isBlank()) return;
    if (methodName == null || methodName.isBlank()) return;
    if (lineNumber <= 0) return;
    String methodKey = dottedClassName + "#" + methodName;
    RESOLVABLE_LINES_BY_METHOD.computeIfAbsent(methodKey, k -> new LineTable()).add(lineNumber);
  }

  public static boolean isLineKey(String key) {
    return parseLineKey(key) != null;
  }

  public static boolean isLineResolvableKey(String key) {
    ParsedLineKey parsed = parseLineKey(key);
    if (parsed == null) return false;
    LineTable table = RESOLVABLE_LINES_BY_METHOD.get(parsed.methodKey);
    if (table == null) {
      tryLoadClassWithoutInitialization(parsed.dottedClassName);
      table = RESOLVABLE_LINES_BY_METHOD.get(parsed.methodKey);
    }
    if (table == null) return false;
    return table.contains(parsed.lineNumber);
  }

  public static KeyStatus keyStatus(String key) {
    boolean includeLineValidation = isLineKey(key);
    boolean lineResolvable = includeLineValidation && isLineResolvableKey(key);
    Boolean lineResolvableValue = null;
    String lineValidation = null;
    if (includeLineValidation) {
      lineResolvableValue = lineResolvable;
      lineValidation = lineResolvable ? "resolvable" : "invalid_line_target";
    }
    return new KeyStatus(
        key,
        countForKey(key),
        lastHitEpochForKey(key),
        lineResolvableValue,
        lineValidation
    );
  }

  public static ActuationState actuationState() {
    long now = System.currentTimeMillis();
    List<SessionActuation> active = activeSessions(now);
    if (active.isEmpty()) {
      return new ActuationState(
          "observe",
          "",
          "",
          "",
          null,
          null,
          "disarmed",
          0
      );
    }
    active.sort(Comparator.comparing(SessionActuation::sessionId));
    SessionActuation selected = active.get(0);
    return new ActuationState(
        "actuate",
        selected.sessionId(),
        selected.actuatorId(),
        selected.targetKey(),
        selected.returnBoolean(),
        selected.expiresAtEpoch(),
        "armed",
        active.size()
    );
  }

  public static RuntimeState runtimeState() {
    return new RuntimeState(
        actuationState(),
        System.currentTimeMillis(),
        ProbeSignalDetector.getApplicationTypeSignal(),
        ProbeSignalDetector.getAppPortSignal()
    );
  }

  public static long countForKey(String key) {
    AtomicLong v = COUNTS.get(key);
    return v == null ? 0L : v.get();
  }

  public static long lastHitEpochForKey(String key) {
    AtomicLong v = LAST_HIT_EPOCH_MS.get(key);
    return v == null ? 0L : v.get();
  }

  public static int branchDecisionByClassMethodLine(
      String dottedClassName,
      String methodName,
      int lineNumber
  ) {
    if (dottedClassName == null || dottedClassName.isBlank() || methodName == null || methodName.isBlank()) {
      return -1;
    }
    if (lineNumber <= 0) return -1;

    String key = dottedClassName + "#" + methodName + ":" + lineNumber;
    List<SessionActuation> matches = activeSessionsForTargetKey(key, System.currentTimeMillis());
    if (matches.size() != 1) return -1;
    SessionActuation selected = matches.get(0);

    // 1 = force jump/taken, 0 = force fallthrough/not-taken
    return selected.returnBoolean() ? 1 : 0;
  }

  public static void reset(String key) {
    if (key == null || key.isEmpty()) return;
    COUNTS.computeIfAbsent(key, k -> new AtomicLong()).set(0L);
    LAST_HIT_EPOCH_MS.computeIfAbsent(key, k -> new AtomicLong()).set(0L);
  }

  public static List<String> lineKeysForClass(String dottedClassName) {
    if (dottedClassName == null || dottedClassName.isBlank()) return Collections.emptyList();
    String classPrefix = dottedClassName.trim() + "#";
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, LineTable> entry : RESOLVABLE_LINES_BY_METHOD.entrySet()) {
      String methodKey = entry.getKey();
      if (!methodKey.startsWith(classPrefix)) continue;
      int[] lines = entry.getValue().snapshot();
      for (int line : lines) {
        out.add(methodKey + ":" + line);
      }
    }
    Collections.sort(out);
    return out;
  }

  public static String escJson(String s) {
    if (s == null) return "";
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '\\':
          out.append("\\\\");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (ch < 32) {
            out.append(String.format("\\u%04x", (int) ch));
          } else {
            out.append(ch);
          }
      }
    }
    return out.toString();
  }

  private static ParsedLineKey parseLineKey(String key) {
    if (key == null || key.isBlank()) return null;
    int hash = key.lastIndexOf('#');
    int colon = key.lastIndexOf(':');
    if (hash <= 0 || colon <= hash + 1 || colon == key.length() - 1) return null;
    String dottedClassName = key.substring(0, hash);
    String methodKey = key.substring(0, colon);
    String linePart = key.substring(colon + 1);
    int lineNumber;
    try {
      lineNumber = Integer.parseInt(linePart);
    } catch (NumberFormatException ignored) {
      return null;
    }
    if (lineNumber <= 0) return null;
    return new ParsedLineKey(dottedClassName, methodKey, lineNumber);
  }

  private static String sanitize(String value) {
    if (value == null) return "";
    return value.trim();
  }

  private static long clampTtlMs(long ttlMs) {
    if (ttlMs < MIN_TTL_MS) return MIN_TTL_MS;
    if (ttlMs > MAX_TTL_MS) return MAX_TTL_MS;
    return ttlMs;
  }

  public static long minTtlMs() {
    return MIN_TTL_MS;
  }

  public static long maxTtlMs() {
    return MAX_TTL_MS;
  }

  private static int activeSessionCount(long now) {
    pruneExpiredSessions(now);
    return SESSION_ACTUATIONS.size();
  }

  private static List<SessionActuation> activeSessions(long now) {
    pruneExpiredSessions(now);
    return new ArrayList<>(SESSION_ACTUATIONS.values());
  }

  private static List<SessionActuation> activeSessionsForTargetKey(String targetKey, long now) {
    List<SessionActuation> active = activeSessions(now);
    List<SessionActuation> matches = new ArrayList<>();
    for (SessionActuation session : active) {
      if (session.targetKey().equals(targetKey)) {
        matches.add(session);
      }
    }
    return matches;
  }

  private static void pruneExpiredSessions(long now) {
    for (Map.Entry<String, SessionActuation> entry : SESSION_ACTUATIONS.entrySet()) {
      SessionActuation session = entry.getValue();
      if (session == null || session.isExpired(now)) {
        SESSION_ACTUATIONS.remove(entry.getKey(), session);
      }
    }
  }

  private static void tryLoadClassWithoutInitialization(String dottedClassName) {
    if (dottedClassName == null || dottedClassName.isBlank()) return;
    for (ClassLoader loader : discoverCandidateClassLoaders()) {
      try {
        Class.forName(dottedClassName, false, loader);
        return;
      } catch (Throwable ignored) {
        // Try the next classloader candidate.
      }
    }
  }

  private static List<ClassLoader> discoverCandidateClassLoaders() {
    LinkedHashSet<ClassLoader> ordered = new LinkedHashSet<>();
    ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    if (contextLoader != null) ordered.add(contextLoader);
    ClassLoader probeLoader = ProbeRuntime.class.getClassLoader();
    if (probeLoader != null) ordered.add(probeLoader);
    ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
    if (systemLoader != null) ordered.add(systemLoader);

    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread == null) continue;
      ClassLoader loader = thread.getContextClassLoader();
      if (loader != null) ordered.add(loader);
    }
    return new ArrayList<>(ordered);
  }

  private static final class ParsedLineKey {
    private final String dottedClassName;
    private final String methodKey;
    private final int lineNumber;

    private ParsedLineKey(String dottedClassName, String methodKey, int lineNumber) {
      this.dottedClassName = dottedClassName;
      this.methodKey = methodKey;
      this.lineNumber = lineNumber;
    }
  }

  private static final class LineTable {
    private volatile int[] lines = new int[0];

    synchronized void add(int line) {
      int[] current = lines;
      int idx = Arrays.binarySearch(current, line);
      if (idx >= 0) return;
      int insertAt = -idx - 1;
      int[] next = new int[current.length + 1];
      System.arraycopy(current, 0, next, 0, insertAt);
      next[insertAt] = line;
      System.arraycopy(current, insertAt, next, insertAt + 1, current.length - insertAt);
      lines = next;
    }

    boolean contains(int line) {
      return Arrays.binarySearch(lines, line) >= 0;
    }

    int[] snapshot() {
      return Arrays.copyOf(lines, lines.length);
    }
  }
}

