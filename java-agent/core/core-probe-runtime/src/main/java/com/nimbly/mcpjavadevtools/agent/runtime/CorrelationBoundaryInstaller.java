package com.nimbly.mcpjavadevtools.agent.runtime;

import java.util.List;

/** Runtime callback used by the assembled agent to refresh exact boundary instrumentation. */
@FunctionalInterface
public interface CorrelationBoundaryInstaller {
  InstallationResult install(List<CorrelationConsumerBoundary> boundaries);

  record InstallationResult(boolean installed, String reasonCode) {
    public static InstallationResult success() {
      return new InstallationResult(true, "ok");
    }

    public static InstallationResult failed(String reasonCode) {
      return new InstallationResult(false, reasonCode);
    }
  }
}
