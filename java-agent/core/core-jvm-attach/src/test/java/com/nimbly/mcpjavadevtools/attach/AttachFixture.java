package com.nimbly.mcpjavadevtools.attach;

/** Disposable JVM target used only for local dynamic-attach verification. */
public final class AttachFixture {
  private AttachFixture() {
  }

  public static void main(String[] args) throws InterruptedException {
    Thread.sleep(60_000L);
  }
}
