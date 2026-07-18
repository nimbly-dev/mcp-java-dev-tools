package com.nimbly.mcpjavadevtools.agent.config;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class AgentConfig {
  public final String host;
  public final int port;
  public final String mode;
  public final String actuatorId;
  public final String probeId;
  public final String actuateTargetKey;
  public final boolean actuateReturnBoolean;
  public final boolean captureEnabled;
  public final int captureMaxKeys;
  public final int captureMaxArgs;
  public final int captureMethodBufferSize;
  public final int capturePreviewMaxChars;
  public final int captureStoredMaxChars;
  public final String captureRedactionMode;
  public final boolean byteBuddyExperimentalCompatibility;
  public final List<String> includePatterns;
  public final List<String> excludePatterns;
  public final String includeSource;
  public final String excludeSource;
  private final List<Pattern> includeRegex;
  private final List<Pattern> excludeRegex;

  private AgentConfig(
      String host,
      int port,
      String mode,
      String actuatorId,
      String probeId,
      String actuateTargetKey,
      boolean actuateReturnBoolean,
      boolean captureEnabled,
      int captureMaxKeys,
      int captureMaxArgs,
      int captureMethodBufferSize,
      int capturePreviewMaxChars,
      int captureStoredMaxChars,
      String captureRedactionMode,
      boolean byteBuddyExperimentalCompatibility,
      List<String> includePatterns,
      List<String> excludePatterns,
      String includeSource,
      String excludeSource
  ) {
    this.host = host;
    this.port = port;
    this.mode = mode;
    this.actuatorId = actuatorId;
    this.probeId = probeId;
    this.actuateTargetKey = actuateTargetKey;
    this.actuateReturnBoolean = actuateReturnBoolean;
    this.captureEnabled = captureEnabled;
    this.captureMaxKeys = captureMaxKeys;
    this.captureMaxArgs = captureMaxArgs;
    this.captureMethodBufferSize = captureMethodBufferSize;
    this.capturePreviewMaxChars = capturePreviewMaxChars;
    this.captureStoredMaxChars = captureStoredMaxChars;
    this.captureRedactionMode = captureRedactionMode;
    this.byteBuddyExperimentalCompatibility = byteBuddyExperimentalCompatibility;
    this.includePatterns = includePatterns;
    this.excludePatterns = excludePatterns;
    this.includeSource = includeSource;
    this.excludeSource = excludeSource;
    this.includeRegex = compilePatterns(includePatterns);
    this.excludeRegex = compilePatterns(excludePatterns);
  }

  public static AgentConfig fromAgentArgs(String args) {
    // Example:
    // -javaagent:probe-agent.jar=host=127.0.0.1;port=9191;mode=observe;actuatorId=none;include=com.nimbly.**;exclude=com.nimbly.mcpjavadevtools.agent.**,**.config.**
    String host = InetAddress.getLoopbackAddress().getHostAddress();
    int port = 9191;
    String mode = readDefaultMode();
    String actuatorId = readDefaultActuatorId();
    String probeId = readDefaultProbeId();
    String actuateTargetKey = readDefaultActuateTargetKey();
    boolean actuateReturnBoolean = readDefaultActuateReturnBoolean();
    boolean captureEnabled = readDefaultCaptureEnabled();
    int captureMaxKeys = readDefaultCaptureMaxKeys();
    int captureMaxArgs = readDefaultCaptureMaxArgs();
    int captureMethodBufferSize = readDefaultCaptureMethodBufferSize();
    int capturePreviewMaxChars = readDefaultCapturePreviewMaxChars();
    int captureStoredMaxChars = readDefaultCaptureStoredMaxChars();
    String captureRedactionMode = readDefaultCaptureRedactionMode();
    boolean byteBuddyExperimentalCompatibility = readDefaultByteBuddyExperimentalCompatibility();
    PatternSetting includeSetting = readDefaultInclude();
    PatternSetting excludeSetting = readDefaultExclude();
    List<String> includePatterns = parseCsv(includeSetting.value);
    List<String> excludePatterns = parseCsv(excludeSetting.value);
    String includeSource = includeSetting.source;
    String excludeSource = excludeSetting.source;

    if (args != null && !args.trim().isEmpty()) {
      String[] parts = args.split(";");
      for (String p : parts) {
        String t = p.trim();
        if (t.isEmpty()) continue;
        int eq = t.indexOf('=');
        if (eq <= 0 || eq == t.length() - 1) continue;
        String k = t.substring(0, eq).trim();
        String v = t.substring(eq + 1).trim();
        if ("host".equalsIgnoreCase(k)) {
          host = v;
        } else if ("port".equalsIgnoreCase(k)) {
          try {
            port = Integer.parseInt(v);
          } catch (NumberFormatException ignored) {
          }
        } else if ("mode".equalsIgnoreCase(k) || "probeMode".equalsIgnoreCase(k)) {
          mode = normalizeMode(v);
        } else if ("actuatorId".equalsIgnoreCase(k) || "actuator".equalsIgnoreCase(k)) {
          actuatorId = normalizeActuatorId(v);
        } else if ("probeId".equalsIgnoreCase(k) || "probe".equalsIgnoreCase(k)) {
          probeId = normalizeActuatorId(v);
        } else if (
            "actuateTarget".equalsIgnoreCase(k)
                || "actuateTargetKey".equalsIgnoreCase(k)
                || "targetKey".equalsIgnoreCase(k)
        ) {
          actuateTargetKey = normalizeTargetKey(v);
        } else if (
            "actuateReturnBool".equalsIgnoreCase(k)
                || "actuateReturnBoolean".equalsIgnoreCase(k)
                || "returnBoolean".equalsIgnoreCase(k)
        ) {
          actuateReturnBoolean = parseBoolean(v, false);
        } else if ("captureEnabled".equalsIgnoreCase(k) || "capture".equalsIgnoreCase(k)) {
          captureEnabled = parseBoolean(v, true);
        } else if ("captureMaxKeys".equalsIgnoreCase(k)) {
          captureMaxKeys = parseInt(v, 1000, 10, 20_000);
        } else if ("captureMaxArgs".equalsIgnoreCase(k)) {
          captureMaxArgs = parseInt(v, 32, 1, 512);
        } else if (
            "captureMethodBufferSize".equalsIgnoreCase(k)
                || "captureBufferSize".equalsIgnoreCase(k)
        ) {
          captureMethodBufferSize = parseInt(v, 3, 1, 32);
        } else if ("capturePreviewMaxChars".equalsIgnoreCase(k)) {
          capturePreviewMaxChars = parseInt(v, 1024, 64, 65_536);
        } else if ("captureStoredMaxChars".equalsIgnoreCase(k)) {
          captureStoredMaxChars = parseInt(v, 16_384, 256, 524_288);
        } else if ("captureRedactionMode".equalsIgnoreCase(k) || "captureRedaction".equalsIgnoreCase(k)) {
          captureRedactionMode = normalizeCaptureRedactionMode(v);
        } else if (
            "byteBuddyExperimental".equalsIgnoreCase(k)
                || "bytebuddyExperimental".equalsIgnoreCase(k)
                || "java21Compatibility".equalsIgnoreCase(k)
                || "java21Compat".equalsIgnoreCase(k)
                || "allowJava21".equalsIgnoreCase(k)
        ) {
          byteBuddyExperimentalCompatibility = parseBoolean(v, false);
        } else if (
            "include".equalsIgnoreCase(k)
                || "includes".equalsIgnoreCase(k)
                || "includePackages".equalsIgnoreCase(k)
        ) {
          includePatterns = parseCsv(v);
          includeSource = "agent_arg:include";
        } else if (
            "exclude".equalsIgnoreCase(k)
                || "excludes".equalsIgnoreCase(k)
                || "excludePackages".equalsIgnoreCase(k)
        ) {
          excludePatterns = parseCsv(v);
          excludeSource = "agent_arg:exclude";
        } else if ("rules".equalsIgnoreCase(k) || "rulesFile".equalsIgnoreCase(k)) {
          // Legacy option is intentionally ignored in generic mode.
          System.err.println("[probe-agent] rulesFile ignored; generic include/exclude mode is active.");
        }
      }
    }

    if (port <= 0) port = 9191;
    mode = normalizeMode(mode);
    actuatorId = normalizeActuatorId(actuatorId);
    probeId = normalizeActuatorId(probeId);
    actuateTargetKey = normalizeTargetKey(actuateTargetKey);
    captureMaxKeys = parseInt(String.valueOf(captureMaxKeys), 1000, 10, 20_000);
    captureMaxArgs = parseInt(String.valueOf(captureMaxArgs), 32, 1, 512);
    captureMethodBufferSize = parseInt(String.valueOf(captureMethodBufferSize), 3, 1, 32);
    capturePreviewMaxChars = parseInt(String.valueOf(capturePreviewMaxChars), 1024, 64, 65_536);
    captureStoredMaxChars = parseInt(String.valueOf(captureStoredMaxChars), 16_384, 256, 524_288);
    captureRedactionMode = normalizeCaptureRedactionMode(captureRedactionMode);
    if (captureStoredMaxChars < capturePreviewMaxChars) {
      captureStoredMaxChars = capturePreviewMaxChars;
    }
    if (!"actuate".equals(mode)) {
      actuatorId = "";
      actuateTargetKey = "";
    }
    return new AgentConfig(
        host,
        port,
        mode,
        actuatorId,
        probeId,
        actuateTargetKey,
        actuateReturnBoolean,
        captureEnabled,
        captureMaxKeys,
        captureMaxArgs,
        captureMethodBufferSize,
        capturePreviewMaxChars,
        captureStoredMaxChars,
        captureRedactionMode,
        byteBuddyExperimentalCompatibility,
        includePatterns,
        excludePatterns,
        includeSource,
        excludeSource
    );
  }

  private static String readDefaultMode() {
    // Priority: JVM system property -> environment variable -> observe (default).
    String fromProp = System.getProperty("mcp.probe.mode");
    if (fromProp != null && !fromProp.trim().isEmpty()) return normalizeMode(fromProp);

    String fromEnv = System.getenv("MCP_PROBE_MODE");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return normalizeMode(fromEnv);

    return "observe";
  }

  private static String readDefaultActuatorId() {
    // Priority: JVM system property -> environment variable -> empty.
    String fromProp = System.getProperty("mcp.probe.actuator.id");
    if (fromProp != null && !fromProp.trim().isEmpty()) return normalizeActuatorId(fromProp);

    String fromEnv = System.getenv("MCP_PROBE_ACTUATOR_ID");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return normalizeActuatorId(fromEnv);

    return "";
  }

  private static String readDefaultProbeId() {
    String fromProp = System.getProperty("mcp.probe.id");
    if (fromProp != null && !fromProp.trim().isEmpty()) return normalizeActuatorId(fromProp);

    String fromEnv = System.getenv("MCP_PROBE_ID");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return normalizeActuatorId(fromEnv);

    return "runtime";
  }

  private static String readDefaultActuateTargetKey() {
    String fromProp = System.getProperty("mcp.probe.actuate.target");
    if (fromProp != null && !fromProp.trim().isEmpty()) return normalizeTargetKey(fromProp);

    String fromEnv = System.getenv("MCP_PROBE_ACTUATE_TARGET");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return normalizeTargetKey(fromEnv);

    return "";
  }

  private static boolean readDefaultActuateReturnBoolean() {
    String fromProp = System.getProperty("mcp.probe.actuate.return.boolean");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseBoolean(fromProp, false);

    String fromEnv = System.getenv("MCP_PROBE_ACTUATE_RETURN_BOOLEAN");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseBoolean(fromEnv, false);

    return false;
  }

  private static boolean readDefaultCaptureEnabled() {
    String fromProp = System.getProperty("mcp.probe.capture.enabled");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseBoolean(fromProp, true);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_ENABLED");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseBoolean(fromEnv, true);

    return true;
  }

  private static int readDefaultCaptureMaxKeys() {
    String fromProp = System.getProperty("mcp.probe.capture.max.keys");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseInt(fromProp, 1000, 10, 20_000);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_MAX_KEYS");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseInt(fromEnv, 1000, 10, 20_000);

    return 1000;
  }

  private static int readDefaultCaptureMaxArgs() {
    String fromProp = System.getProperty("mcp.probe.capture.max.args");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseInt(fromProp, 32, 1, 512);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_MAX_ARGS");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseInt(fromEnv, 32, 1, 512);

    return 32;
  }

  private static int readDefaultCaptureMethodBufferSize() {
    String fromProp = System.getProperty("mcp.probe.capture.method.buffer.size");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseInt(fromProp, 3, 1, 32);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_METHOD_BUFFER_SIZE");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseInt(fromEnv, 3, 1, 32);

    return 3;
  }

  private static int readDefaultCapturePreviewMaxChars() {
    String fromProp = System.getProperty("mcp.probe.capture.preview.max.chars");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseInt(fromProp, 1024, 64, 65_536);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_PREVIEW_MAX_CHARS");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseInt(fromEnv, 1024, 64, 65_536);

    return 1024;
  }

  private static int readDefaultCaptureStoredMaxChars() {
    String fromProp = System.getProperty("mcp.probe.capture.stored.max.chars");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseInt(fromProp, 16_384, 256, 524_288);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_STORED_MAX_CHARS");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseInt(fromEnv, 16_384, 256, 524_288);

    return 16_384;
  }

  private static String readDefaultCaptureRedactionMode() {
    String fromProp = System.getProperty("mcp.probe.capture.redaction");
    if (fromProp != null && !fromProp.trim().isEmpty()) return normalizeCaptureRedactionMode(fromProp);

    String fromEnv = System.getenv("MCP_PROBE_CAPTURE_REDACTION");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return normalizeCaptureRedactionMode(fromEnv);

    return "basic";
  }

  private static boolean readDefaultByteBuddyExperimentalCompatibility() {
    String fromProp = System.getProperty("mcp.probe.bytebuddy.experimental");
    if (fromProp != null && !fromProp.trim().isEmpty()) return parseBoolean(fromProp, false);

    String fromEnv = System.getenv("MCP_PROBE_BYTEBUDDY_EXPERIMENTAL");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return parseBoolean(fromEnv, false);

    String fromLegacyProp = System.getProperty("mcp.probe.allow.java21");
    if (fromLegacyProp != null && !fromLegacyProp.trim().isEmpty()) return parseBoolean(fromLegacyProp, false);

    String fromLegacyEnv = System.getenv("MCP_PROBE_ALLOW_JAVA21");
    if (fromLegacyEnv != null && !fromLegacyEnv.trim().isEmpty()) return parseBoolean(fromLegacyEnv, false);

    return false;
  }

  private static String normalizeMode(String raw) {
    if (raw == null) return "observe";
    String m = raw.trim().toLowerCase();
    if ("actuate".equals(m)) return "actuate";
    return "observe";
  }

  private static String normalizeActuatorId(String raw) {
    if (raw == null) return "";
    return raw.trim();
  }

  private static String normalizeTargetKey(String raw) {
    if (raw == null) return "";
    return raw.trim();
  }

  private static boolean parseBoolean(String raw, boolean defaultValue) {
    if (raw == null) return defaultValue;
    String v = raw.trim().toLowerCase();
    if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v)) return true;
    if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "n".equals(v)) return false;
    return defaultValue;
  }

  private static int parseInt(String raw, int defaultValue, int minValue, int maxValue) {
    if (raw == null || raw.trim().isEmpty()) return defaultValue;
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed < minValue) return minValue;
      if (parsed > maxValue) return maxValue;
      return parsed;
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private static String normalizeCaptureRedactionMode(String raw) {
    if (raw == null) return "basic";
    String mode = raw.trim().toLowerCase();
    if ("off".equals(mode)) return "off";
    return "basic";
  }

  private static PatternSetting readDefaultInclude() {
    // Priority: JVM system property -> environment variable -> global fallback.
    String fromProp = System.getProperty("mcp.probe.include");
    if (fromProp != null && !fromProp.trim().isEmpty()) {
      return new PatternSetting(fromProp, "system_property:mcp.probe.include");
    }

    String fromEnv = System.getenv("MCP_PROBE_INCLUDE");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) {
      return new PatternSetting(fromEnv, "env:MCP_PROBE_INCLUDE");
    }

    // Dynamic default: derive app base package from the launched class/jar.
    String inferred = inferIncludeFromStartup();
    if (inferred != null && !inferred.trim().isEmpty()) {
      return new PatternSetting(inferred, "inferred:sun.java.command");
    }

    // Fail closed: require explicit include if inference is unavailable.
    return new PatternSetting("", "none");
  }

  private static PatternSetting readDefaultExclude() {
    // Priority: JVM system property -> environment variable -> safe default exclusions.
    String fromProp = System.getProperty("mcp.probe.exclude");
    if (fromProp != null && !fromProp.trim().isEmpty()) {
      return new PatternSetting(fromProp, "system_property:mcp.probe.exclude");
    }

    String fromEnv = System.getenv("MCP_PROBE_EXCLUDE");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) {
      return new PatternSetting(fromEnv, "env:MCP_PROBE_EXCLUDE");
    }

    return new PatternSetting("com.nimbly.mcpjavadevtools.agent.**", "default");
  }

  private static String inferIncludeFromStartup() {
    String command = System.getProperty("sun.java.command");
    if (command == null || command.trim().isEmpty()) return null;

    String entry = firstToken(command.trim());
    if (entry == null || entry.isEmpty()) return null;

    if (entry.endsWith(".jar")) {
      String mainClass = readStartClassFromJar(entry);
      String include = classNameToPackageInclude(mainClass);
      if (include != null) return include;
    }

    // Class launch mode (java com.example.Main ...)
    if (entry.indexOf('.') > 0 && entry.indexOf('/') < 0 && entry.indexOf('\\') < 0) {
      if (isFrameworkLauncherClass(entry)) {
        String loaderMain = readSpringLoaderMainClass();
        String inferredFromLoaderMain = classNameToPackageInclude(loaderMain);
        if (inferredFromLoaderMain != null) {
          return inferredFromLoaderMain;
        }
        // Spring launcher mode may hide the app main class from sun.java.command.
        // Fall back to broad include to avoid missing app instrumentation entirely.
        return "**";
      }
      return classNameToPackageInclude(entry);
    }

    return null;
  }

  private static String firstToken(String value) {
    if (value == null || value.isEmpty()) return null;
    if (value.charAt(0) == '"') {
      int end = value.indexOf('"', 1);
      if (end > 1) return value.substring(1, end);
      return value.substring(1);
    }
    int space = value.indexOf(' ');
    return space > 0 ? value.substring(0, space) : value;
  }

  private static String readStartClassFromJar(String jarPath) {
    File jar = new File(jarPath);
    if (!jar.isAbsolute()) {
      String userDir = System.getProperty("user.dir");
      if (userDir != null && !userDir.trim().isEmpty()) {
        jar = new File(userDir, jarPath);
      }
    }
    if (!jar.exists() || !jar.isFile()) return null;

    try (JarFile jf = new JarFile(jar)) {
      Manifest mf = jf.getManifest();
      if (mf == null) return null;
      Attributes attrs = mf.getMainAttributes();
      if (attrs == null) return null;

      // Spring Boot executable jar
      String startClass = attrs.getValue("Start-Class");
      if (startClass != null && !startClass.trim().isEmpty()) return startClass.trim();

      // Plain executable jar
      String mainClass = attrs.getValue("Main-Class");
      if (mainClass != null && !mainClass.trim().isEmpty()) return mainClass.trim();
    } catch (IOException ignored) {
    }
    return null;
  }

  private static String classNameToPackageInclude(String fqcn) {
    if (fqcn == null) return null;
    String c = fqcn.trim();
    int idx = c.lastIndexOf('.');
    if (idx <= 0) return null;
    return c.substring(0, idx) + ".**";
  }

  private static boolean isFrameworkLauncherClass(String fqcn) {
    if (fqcn == null || fqcn.trim().isEmpty()) return false;
    String value = fqcn.trim();
    return "org.springframework.boot.loader.JarLauncher".equals(value)
        || "org.springframework.boot.loader.PropertiesLauncher".equals(value)
        || "org.springframework.boot.loader.WarLauncher".equals(value)
        || "org.springframework.boot.loader.launch.JarLauncher".equals(value)
        || "org.springframework.boot.loader.launch.PropertiesLauncher".equals(value)
        || "org.springframework.boot.loader.launch.WarLauncher".equals(value);
  }

  private static String readSpringLoaderMainClass() {
    String fromProp = System.getProperty("loader.main");
    if (fromProp != null && !fromProp.trim().isEmpty()) return fromProp.trim();

    String fromEnv = System.getenv("LOADER_MAIN");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) return fromEnv.trim();
    return null;
  }

  public List<String> broadIncludePatterns() {
    List<String> broad = new ArrayList<>();
    for (String pattern : includePatterns) {
      if (isBroadIncludePattern(pattern)) {
        broad.add(pattern);
      }
    }
    return broad;
  }

  private static boolean isBroadIncludePattern(String pattern) {
    if (pattern == null) return false;
    String raw = pattern.trim();
    if (raw.isEmpty()) return false;
    if ("**".equals(raw) || "*".equals(raw)) return true;

    String normalized = raw;
    if (normalized.endsWith(".**")) {
      normalized = normalized.substring(0, normalized.length() - 3);
    }
    normalized = normalized.replace("*", "");
    while (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isEmpty()) return true;

    String[] segments = normalized.split("\\.");
    return segments.length <= 1;
  }

  public boolean shouldInstrument(String dottedClassName) {
    if (dottedClassName == null || dottedClassName.isEmpty()) return false;
    if (!matchesAny(includeRegex, dottedClassName)) return false;
    return !matchesAny(excludeRegex, dottedClassName);
  }

  private static boolean matchesAny(List<Pattern> patterns, String value) {
    for (Pattern p : patterns) {
      if (p.matcher(value).matches()) return true;
    }
    return false;
  }

  private static List<String> parseCsv(String raw) {
    if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
    List<String> out = new ArrayList<>();
    String[] parts = raw.split(",");
    for (String p : parts) {
      String t = p.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  private static List<Pattern> compilePatterns(List<String> patterns) {
    List<Pattern> out = new ArrayList<>();
    for (String p : patterns) {
      String t = p == null ? "" : p.trim();
      if (t.isEmpty()) continue;
      out.add(Pattern.compile(toRegex(t)));
    }
    return out;
  }

  private static String toRegex(String globOrPrefix) {
    // Supports '*' and '**' glob.
    // If no wildcard exists, treat likely class FQCNs as exact class targets;
    // otherwise treat as package prefix.
    boolean hasWildcard = globOrPrefix.indexOf('*') >= 0;
    if (!hasWildcard) {
      String candidate = globOrPrefix.endsWith(".")
          ? globOrPrefix.substring(0, globOrPrefix.length() - 1)
          : globOrPrefix;
      if (isLikelyExactClassName(candidate)) {
        return "^" + Pattern.quote(candidate) + "(\\$.*)?$";
      }
    }
    String g;
    if (hasWildcard) {
      g = globOrPrefix;
    } else if (globOrPrefix.endsWith(".")) {
      g = globOrPrefix + "**";
    } else {
      g = globOrPrefix + ".**";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("^");
    for (int i = 0; i < g.length(); i++) {
      char c = g.charAt(i);
      if (c == '*') {
        boolean dbl = i + 1 < g.length() && g.charAt(i + 1) == '*';
        if (dbl) {
          sb.append(".*");
          i++;
        } else {
          sb.append("[^.]*");
        }
      } else {
        if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) sb.append('\\');
        sb.append(c);
      }
    }
    sb.append("$");
    return sb.toString();
  }

  private static boolean isLikelyExactClassName(String value) {
    if (value == null) return false;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return false;
    int lastDot = trimmed.lastIndexOf('.');
    if (lastDot <= 0 || lastDot >= trimmed.length() - 1) return false;
    String lastSegment = trimmed.substring(lastDot + 1);
    if (lastSegment.indexOf('*') >= 0) return false;
    if (lastSegment.indexOf('$') >= 0) return true;
    for (int i = 0; i < lastSegment.length(); i++) {
      if (Character.isUpperCase(lastSegment.charAt(i))) return true;
    }
    return false;
  }

  private static final class PatternSetting {
    final String value;
    final String source;

    PatternSetting(String value, String source) {
      this.value = value == null ? "" : value;
      this.source = source == null || source.trim().isEmpty() ? "unknown" : source;
    }
  }
}


