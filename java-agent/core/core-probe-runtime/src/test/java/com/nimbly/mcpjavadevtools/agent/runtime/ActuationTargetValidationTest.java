package com.nimbly.mcpjavadevtools.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActuationTargetValidationTest {
  private static final String CLASS_NAME = "example.ActuationTarget";
  private static final String METHOD_NAME = "guard";

  @BeforeEach
  void registerTargetLines() {
    ProbeRuntime.configure("observe", "", "", false);
    ProbeRuntime.registerResolvableLine(CLASS_NAME, METHOD_NAME, 20);
    ProbeRuntime.registerResolvableLine(CLASS_NAME, METHOD_NAME, 30);
    ProbeRuntime.registerActuatableLine(CLASS_NAME, METHOD_NAME, 30);
  }

  @Test
  void distinguishesUnresolvedAndNonConditionalLines() {
    assertFalse(ProbeRuntime.isLineResolvableKey(CLASS_NAME + "#" + METHOD_NAME + ":99"));
    assertFalse(ProbeRuntime.isLineActuatableKey(CLASS_NAME + "#" + METHOD_NAME + ":20"));
    assertTrue(ProbeRuntime.isLineActuatableKey(CLASS_NAME + "#" + METHOD_NAME + ":30"));
  }

  @Test
  void rejectsMalformedLineKeysAsNotActuatable() {
    assertFalse(ProbeRuntime.isLineActuatableKey("not-a-strict-line-key"));
  }
}
