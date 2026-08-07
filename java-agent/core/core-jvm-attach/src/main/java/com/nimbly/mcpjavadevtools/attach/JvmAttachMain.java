package com.nimbly.mcpjavadevtools.attach;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Java 21 local-only helper for dynamic Sidecar Agent lifecycle operations. */
public final class JvmAttachMain {
  private static final String AGENT_CLASS =
      "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent";
  private static final int MAX_DISCOVERED_PROCESSES = 128;
  private static final int MAX_REPORTED_CLASSES = 128;
  private static final int MAX_IDENTITY_HINT_LENGTH = 128;

  private JvmAttachMain() {
  }

  public static void main(String[] args) {
    System.out.println(run(args).toJson());
  }

  static AttachResult run(String[] args) {
    Command command = Command.parse(args);
    if (!command.valid()) {
      return AttachResult.failed("unknown", "invalid_arguments");
    }
    if ("discover".equals(command.operation())) {
      return discover();
    }
    return executeMutation(command);
  }

  private static AttachResult discover() {
    List<String> pids = new ArrayList<>();
    List<JvmCandidate> candidates = new ArrayList<>();
    for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
      if (candidates.size() == MAX_DISCOVERED_PROCESSES) {
        break;
      }
      if (isNumericPid(descriptor.id())) {
        pids.add(descriptor.id());
        candidates.add(candidate(descriptor));
      }
    }
    return AttachResult.discovered(pids, candidates);
  }

  private static JvmCandidate candidate(VirtualMachineDescriptor descriptor) {
    String displayName = descriptor.displayName();
    String descriptorIdentity = descriptorIdentity(displayName);
    String executableIdentity = executableIdentity(descriptor.id());
    String identityHint = descriptorIdentity != null ? descriptorIdentity : executableIdentity;
    String identitySource = descriptorIdentity != null
        ? "sanitized_attach_descriptor"
        : executableIdentity != null ? "sanitized_executable_basename" : "unavailable";
    Set<String> evidence = new LinkedHashSet<>();
    if (isSpringBootLauncher(displayName)) {
      evidence.add("spring_boot_launcher");
    }
    if (isExecutableJar(descriptorIdentity)) {
      evidence.add("executable_jar_name");
    }
    String frameworkHint = evidence.isEmpty() ? "unknown" : "spring_boot_candidate";
    return new JvmCandidate(
        descriptor.id(),
        identityHint,
        identitySource,
        frameworkHint,
        List.copyOf(evidence),
        processStartEpochMs(descriptor.id()));
  }

  private static String descriptorIdentity(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return null;
    }
    String[] tokens = displayName.trim().split("\\s+");
    for (int index = 0; index < tokens.length - 1; index++) {
      if ("-jar".equals(stripQuotes(tokens[index]))) {
        String jarName = basename(stripQuotes(tokens[index + 1]));
        if (isExecutableJar(jarName)) {
          return sanitizeToken(jarName);
        }
      }
    }
    for (String token : tokens) {
      String normalized = stripQuotes(token);
      if (isJavaMainClass(normalized)) {
        return sanitizeToken(normalized);
      }
    }
    return null;
  }

  private static String executableIdentity(String pid) {
    try {
      return ProcessHandle.of(Long.parseLong(pid))
          .flatMap(handle -> handle.info().command())
          .map(JvmAttachMain::basename)
          .map(JvmAttachMain::sanitizeToken)
          .orElse(null);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static Long processStartEpochMs(String pid) {
    try {
      return ProcessHandle.of(Long.parseLong(pid))
          .flatMap(handle -> handle.info().startInstant())
          .map(Instant::toEpochMilli)
          .orElse(null);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static boolean isSpringBootLauncher(String displayName) {
    if (displayName == null) {
      return false;
    }
    String normalized = displayName.toLowerCase(Locale.ROOT);
    return normalized.contains("org.springframework.boot.loader.jarlauncher")
        || normalized.contains("org.springframework.boot.loader.propertieslauncher")
        || normalized.contains("org.springframework.boot.loader.warlauncher")
        || normalized.contains("org.springframework.boot.loader.launch.jarlauncher")
        || normalized.contains("org.springframework.boot.loader.launch.propertieslauncher")
        || normalized.contains("org.springframework.boot.loader.launch.warlauncher");
  }

  private static boolean isExecutableJar(String value) {
    return value != null && value.toLowerCase(Locale.ROOT).endsWith(".jar");
  }

  private static boolean isJavaMainClass(String value) {
    if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
        || value.contains("=") || !value.contains(".")) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!(Character.isLetterOrDigit(character) || character == '.'
          || character == '_' || character == '$')) {
        return false;
      }
    }
    char first = value.charAt(0);
    return Character.isLetter(first) || first == '_' || first == '$';
  }

  private static String basename(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return separator >= 0 ? normalized.substring(separator + 1) : normalized;
  }

  private static String sanitizeToken(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    StringBuilder sanitized = new StringBuilder();
    for (int index = 0; index < value.length() && sanitized.length() < MAX_IDENTITY_HINT_LENGTH; index++) {
      char character = value.charAt(index);
      if (Character.isLetterOrDigit(character) || character == '.' || character == '_'
          || character == '-' || character == '$') {
        sanitized.append(character);
      } else {
        sanitized.append('_');
      }
    }
    return sanitized.isEmpty() ? null : sanitized.toString();
  }

  private static String stripQuotes(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("^[\\\"']|[\\\"']$", "");
  }

  private static AttachResult executeMutation(Command command) {
    if (!command.confirmed()) {
      return AttachResult.failed(command.operation(), "confirmation_required");
    }
    if (!isNumericPid(command.pid())) {
      return AttachResult.failed(command.operation(), "pid_invalid");
    }
    if (command.expectedProcessStartEpochMs() == null) {
      return AttachResult.failed(command.operation(), "process_start_required");
    }
    if (!ProcessHandle.of(Long.parseLong(command.pid())).isPresent()) {
      return AttachResult.failed(command.operation(), "pid_not_live");
    }
    String processStartReason = validateProcessStart(command);
    if (processStartReason != null) {
      return AttachResult.failed(command.operation(), processStartReason);
    }
    if (Long.toString(ProcessHandle.current().pid()).equals(command.pid())) {
      return AttachResult.failed(command.operation(), "self_attach_forbidden");
    }
    if (!isRepositoryOwnedAgent(command.agentJar())) {
      return AttachResult.failed(command.operation(), "agent_artifact_invalid");
    }
    return loadAgent(command);
  }

  private static AttachResult loadAgent(Command command) {
    Path reportFile = null;
    VirtualMachine machine = null;
    try {
      reportFile = Files.createTempFile("mcp-jvm-lifecycle-", ".report");
      Files.deleteIfExists(reportFile);
      machine = VirtualMachine.attach(command.pid());
      String processStartReason = validateProcessStart(command);
      if (processStartReason != null) {
        return AttachResult.failed(command.operation(), processStartReason);
      }
      String runtimeReason = validateTargetRuntime(machine);
      if (runtimeReason != null) {
        return AttachResult.failed(command.operation(), runtimeReason);
      }
      machine.loadAgent(command.agentJar().toString(), command.agentArgs(reportFile));
      return readLifecycleReport(command.operation(), reportFile);
    } catch (AttachNotSupportedException exception) {
      return AttachResult.failed(command.operation(), attachReasonCode(exception.getMessage()));
    } catch (AgentLoadException exception) {
      return AttachResult.failed(command.operation(), agentLoadReasonCode(exception.getMessage()));
    } catch (AgentInitializationException exception) {
      AttachResult report = readLifecycleReport(command.operation(), reportFile);
      return "agent_report_missing".equals(report.reasonCode())
          ? AttachResult.failed(command.operation(), "agent_initialization_failed")
          : report;
    } catch (IOException exception) {
      return AttachResult.failed(command.operation(), "attach_io_failed");
    } finally {
      detach(machine);
      deleteReport(reportFile);
    }
  }

  private static AttachResult readLifecycleReport(String operation, Path reportFile) {
    if (reportFile == null || !Files.isRegularFile(reportFile)) {
      return AttachResult.failed(operation, "agent_report_missing");
    }
    Properties report = new Properties();
    try (var reader = Files.newBufferedReader(reportFile)) {
      report.load(reader);
    } catch (IOException exception) {
      return AttachResult.failed(operation, "agent_report_unreadable");
    }
    String outcome = report.getProperty("outcome", "");
    String reasonCode = report.getProperty("reasonCode", "");
    if (outcome.isBlank() || reasonCode.isBlank()) {
      return AttachResult.failed(operation, "agent_report_invalid");
    }
    List<String> classes = parseClasses(report.getProperty("nonRestorableClasses", ""));
    return AttachResult.reported(operation, outcome, reasonCode, classes);
  }

  private static List<String> parseClasses(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    String[] values = value.split(",");
    List<String> classes = new ArrayList<>();
    for (String className : values) {
      if (classes.size() == MAX_REPORTED_CLASSES) {
        break;
      }
      if (!className.isBlank()) {
        classes.add(className.trim());
      }
    }
    return List.copyOf(classes);
  }

  static boolean isRepositoryOwnedAgent(Path agentJar) {
    if (agentJar == null || !Files.isRegularFile(agentJar)) {
      return false;
    }
    try (JarFile jar = new JarFile(agentJar.toFile())) {
      Attributes attributes = jar.getManifest().getMainAttributes();
      return AGENT_CLASS.equals(attributes.getValue("Premain-Class"))
          && AGENT_CLASS.equals(attributes.getValue("Agent-Class"))
          && jar.getJarEntry(AGENT_CLASS.replace('.', '/') + ".class") != null;
    } catch (IOException | NullPointerException exception) {
      return false;
    }
  }

  private static void detach(VirtualMachine machine) {
    if (machine == null) {
      return;
    }
    try {
      machine.detach();
    } catch (IOException ignored) {
      // The operation result is already determined; session cleanup is best effort here.
    }
  }

  private static String validateTargetRuntime(VirtualMachine machine) {
    try {
      String feature = machine.getSystemProperties().getProperty("java.specification.version", "");
      return javaFeatureAtLeast21(feature) ? null : "target_java_version_unsupported";
    } catch (IOException | InternalError exception) {
      return "target_runtime_unverified";
    }
  }

  private static String validateProcessStart(Command command) {
    Long actual = processStartEpochMs(command.pid());
    if (actual == null) {
      return "target_process_start_unavailable";
    }
    return actual.equals(command.expectedProcessStartEpochMs())
        ? null : "pid_reused_or_process_identity_mismatch";
  }

  private static boolean javaFeatureAtLeast21(String version) {
    if (version == null || version.isBlank()) {
      return false;
    }
    try {
      return Integer.parseInt(version.trim()) >= 21;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static void deleteReport(Path reportFile) {
    if (reportFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(reportFile);
    } catch (IOException ignored) {
      // The report contains only bounded lifecycle state and is created under the local temp directory.
    }
  }

  private static boolean isNumericPid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      return Long.parseLong(value) > 0L;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static String attachReasonCode(String message) {
    String normalized = message == null ? "" : message.toLowerCase();
    if (normalized.contains("attach mechanism")) {
      return "attach_mechanism_disabled";
    }
    return "attach_unsupported";
  }

  private static String agentLoadReasonCode(String message) {
    String normalized = message == null ? "" : message.toLowerCase();
    if (normalized.contains("dynamic agent loading")) {
      return "dynamic_agent_loading_disabled";
    }
    return "agent_load_failed";
  }

  record AttachResult(String operation, String outcome, String reasonCode,
                      List<String> pids, List<JvmCandidate> candidates,
                      List<String> nonRestorableClasses) {
    static AttachResult discovered(List<String> pids, List<JvmCandidate> candidates) {
      return new AttachResult("discover", "unverified", "jvm_discovery_unverified",
          pids, candidates, List.of());
    }

    static AttachResult reported(String operation, String outcome, String reasonCode,
                                 List<String> classes) {
      return new AttachResult(operation, outcome, reasonCode, List.of(), List.of(), classes);
    }

    static AttachResult failed(String operation, String reasonCode) {
      return new AttachResult(operation, "blocked", reasonCode, List.of(), List.of(), List.of());
    }

    String toJson() {
      return "{\"operation\":\"" + escape(operation) + "\",\"outcome\":\""
          + escape(outcome) + "\",\"reasonCode\":\"" + escape(reasonCode)
          + "\",\"pids\":" + jsonArray(pids) + ",\"candidates\":"
          + jsonCandidates(candidates) + ",\"nonRestorableClasses\":"
          + jsonArray(nonRestorableClasses) + "}";
    }
  }

  record JvmCandidate(String pid, String identityHint, String identitySource,
                      String frameworkHint, List<String> frameworkEvidence,
                      Long processStartEpochMs) {
    String toJson() {
      return "{\"pid\":\"" + escape(pid) + "\",\"identityHint\":"
          + nullableString(identityHint) + ",\"identitySource\":\""
          + escape(identitySource) + "\",\"frameworkHint\":\""
          + escape(frameworkHint) + "\",\"frameworkEvidence\":"
          + jsonArray(frameworkEvidence) + ",\"processStartEpochMs\":"
          + nullableNumber(processStartEpochMs) + "}";
    }
  }

  private record Command(boolean valid, String operation, String pid, Path agentJar,
                         Long expectedProcessStartEpochMs, boolean confirmed,
                         String requestedAgentArgs) {
    static Command parse(String[] args) {
      if (args == null || args.length == 0 || "discover".equals(args[0])) {
        return args == null || args.length == 0
            ? invalid()
            : new Command(args.length == 1, "discover", "", null, null, false, "");
      }
      if (!"attach".equals(args[0]) && !"deactivate".equals(args[0])) {
        return invalid();
      }
      String pid = "";
      String agentJar = "";
      Long expectedProcessStartEpochMs = null;
      String agentArgs = "";
      boolean confirm = false;
      for (int index = 1; index < args.length; index += 2) {
        if (index + 1 >= args.length) {
          return invalid();
        }
        String option = args[index];
        String value = args[index + 1];
        if ("--pid".equals(option)) {
          pid = value;
        } else if ("--agent-jar".equals(option)) {
          agentJar = value;
        } else if ("--expected-process-start-epoch-ms".equals(option)) {
          expectedProcessStartEpochMs = parsePositiveLong(value);
        } else if ("--agent-args".equals(option) && "attach".equals(args[0])) {
          agentArgs = value;
        } else if ("--confirm".equals(option)) {
          confirm = "true".equalsIgnoreCase(value);
        } else {
          return invalid();
        }
      }
      if (pid.isBlank() || agentJar.isBlank()) {
        return invalid();
      }
      if (containsReservedLifecycleArgument(agentArgs)) {
        return invalid();
      }
      return new Command(true, args[0], pid, Path.of(agentJar), expectedProcessStartEpochMs,
          confirm, agentArgs);
    }

    private static boolean containsReservedLifecycleArgument(String agentArgs) {
      if (agentArgs == null || agentArgs.isBlank()) {
        return false;
      }
      for (String part : agentArgs.split(";")) {
        String key = part.split("=", 2)[0].trim();
        if ("action".equalsIgnoreCase(key) || "reportFile".equalsIgnoreCase(key)) {
          return true;
        }
      }
      return false;
    }

    String agentArgs(Path reportFile) {
      String action = "deactivate".equals(operation) ? "action=deactivate" : "action=attach";
      String report = "reportFile=" + reportFile.toAbsolutePath();
      return requestedAgentArgs.isBlank() ? action + ";" + report
          : requestedAgentArgs + ";" + action + ";" + report;
    }

    private static Command invalid() {
      return new Command(false, "unknown", "", null, null, false, "");
    }

    private static Long parsePositiveLong(String value) {
      try {
        long parsed = Long.parseLong(value);
        return parsed > 0L ? parsed : null;
      } catch (NumberFormatException exception) {
        return null;
      }
    }
  }

  private static String jsonArray(List<String> values) {
    StringBuilder output = new StringBuilder("[");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      output.append('"').append(escape(values.get(index))).append('"');
    }
    return output.append(']').toString();
  }

  private static String jsonCandidates(List<JvmCandidate> candidates) {
    StringBuilder output = new StringBuilder("[");
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      output.append(candidates.get(index).toJson());
    }
    return output.append(']').toString();
  }

  private static String nullableString(String value) {
    return value == null ? "null" : "\"" + escape(value) + "\"";
  }

  private static String nullableNumber(Long value) {
    return value == null ? "null" : Long.toString(value);
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
