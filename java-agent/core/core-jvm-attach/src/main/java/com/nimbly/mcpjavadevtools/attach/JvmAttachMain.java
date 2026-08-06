package com.nimbly.mcpjavadevtools.attach;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Java 21 local-only helper for dynamic Sidecar Agent lifecycle operations. */
public final class JvmAttachMain {
  private static final String AGENT_CLASS =
      "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent";
  private static final int MAX_DISCOVERED_PROCESSES = 128;
  private static final int MAX_REPORTED_CLASSES = 128;

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
    for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
      if (pids.size() == MAX_DISCOVERED_PROCESSES) {
        break;
      }
      if (isNumericPid(descriptor.id())) {
        pids.add(descriptor.id());
      }
    }
    return AttachResult.discovered(pids);
  }

  private static AttachResult executeMutation(Command command) {
    if (!command.confirmed()) {
      return AttachResult.failed(command.operation(), "confirmation_required");
    }
    if (!isNumericPid(command.pid())) {
      return AttachResult.failed(command.operation(), "pid_invalid");
    }
    if (!ProcessHandle.of(Long.parseLong(command.pid())).isPresent()) {
      return AttachResult.failed(command.operation(), "pid_not_live");
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
                      List<String> pids, List<String> nonRestorableClasses) {
    static AttachResult discovered(List<String> pids) {
      return new AttachResult("discover", "unverified", "jvm_discovery_unverified", pids, List.of());
    }

    static AttachResult reported(String operation, String outcome, String reasonCode,
                                 List<String> classes) {
      return new AttachResult(operation, outcome, reasonCode, List.of(), classes);
    }

    static AttachResult failed(String operation, String reasonCode) {
      return new AttachResult(operation, "blocked", reasonCode, List.of(), List.of());
    }

    String toJson() {
      return "{\"operation\":\"" + escape(operation) + "\",\"outcome\":\""
          + escape(outcome) + "\",\"reasonCode\":\"" + escape(reasonCode)
          + "\",\"pids\":" + jsonArray(pids) + ",\"nonRestorableClasses\":"
          + jsonArray(nonRestorableClasses) + "}";
    }
  }

  private record Command(boolean valid, String operation, String pid, Path agentJar,
                         boolean confirmed, String requestedAgentArgs) {
    static Command parse(String[] args) {
      if (args == null || args.length == 0 || "discover".equals(args[0])) {
        return args == null || args.length == 0
            ? invalid()
            : new Command(args.length == 1, "discover", "", null, false, "");
      }
      if (!"attach".equals(args[0]) && !"deactivate".equals(args[0])) {
        return invalid();
      }
      String pid = "";
      String agentJar = "";
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
      return new Command(true, args[0], pid, Path.of(agentJar), confirm, agentArgs);
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
      return new Command(false, "unknown", "", null, false, "");
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

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
