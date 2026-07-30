package com.nimbly.mcpjavadevtools.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

class JvmAttachMainTest {
  @Test
  void failsClosedWhenMutationConfirmationIsMissing() {
    JvmAttachMain.AttachResult result = JvmAttachMain.run(new String[] {
        "attach", "--pid", "123", "--agent-jar", "missing.jar", "--confirm", "false"
    });

    assertEquals("blocked", result.outcome());
    assertEquals("confirmation_required", result.reasonCode());
  }

  @Test
  void rejectsNonNumericPidBeforeTryingToLoadAnAgent() {
    JvmAttachMain.AttachResult result = JvmAttachMain.run(new String[] {
        "deactivate", "--pid", "not-a-pid", "--agent-jar", "missing.jar", "--confirm", "true"
    });

    assertEquals("blocked", result.outcome());
    assertEquals("pid_invalid", result.reasonCode());
  }

  @Test
  void discoveryDoesNotClaimTargetAttachabilityOrProbeState() {
    JvmAttachMain.AttachResult result = JvmAttachMain.run(new String[] {"discover"});

    assertEquals("discover", result.operation());
    assertEquals("unverified", result.outcome());
    assertEquals("jvm_discovery_unverified", result.reasonCode());
    assertFalse(result.toJson().contains("displayName"));
  }

  @Test
  void rejectsCallerControlOfTheLifecycleAction() {
    JvmAttachMain.AttachResult result = JvmAttachMain.run(new String[] {
        "attach", "--pid", "123", "--agent-jar", "agent.jar", "--confirm", "true",
        "--agent-args", "action=deactivate"
    });

    assertEquals("blocked", result.outcome());
    assertEquals("invalid_arguments", result.reasonCode());
  }

  @Test
  void acceptsManifestValidAgentWithContainerMountName() throws IOException {
    Path mountedAgent = Files.createTempFile("mcp-java-dev-tools-agent", ".jar");
    try {
      writeRepositoryAgentManifest(mountedAgent);
      assertTrue(JvmAttachMain.isRepositoryOwnedAgent(mountedAgent));
    } finally {
      Files.deleteIfExists(mountedAgent);
    }
  }

  private static void writeRepositoryAgentManifest(Path agentJar) throws IOException {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Premain-Class", "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent");
    attributes.putValue("Agent-Class", "com.nimbly.mcpjavadevtools.agent.bootstrap.ProbeAgent");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(agentJar), manifest)) {
      output.putNextEntry(new JarEntry(
          "com/nimbly/mcpjavadevtools/agent/bootstrap/ProbeAgent.class"));
      output.closeEntry();
    }
  }
}
