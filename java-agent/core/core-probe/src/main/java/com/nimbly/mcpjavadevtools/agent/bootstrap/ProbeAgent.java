package com.nimbly.mcpjavadevtools.agent.bootstrap;

import java.lang.instrument.Instrumentation;

public final class ProbeAgent {
  private ProbeAgent() {}

  public static void premain(String agentArgs, Instrumentation inst) {
    ProbeLifecycle.premain(agentArgs, inst);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    ProbeLifecycle.agentmain(agentArgs, inst);
  }
}

