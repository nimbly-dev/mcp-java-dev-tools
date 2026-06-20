package com.nimbly.mcpjavadevtools.agent.capture;

import java.lang.management.ManagementFactory;

final class ThreadAllocationMetrics {
  private static final com.sun.management.ThreadMXBean THREAD_MX_BEAN = resolveThreadMxBean();
  private static final boolean THREAD_ALLOC_SUPPORTED = supportsThreadAllocation(THREAD_MX_BEAN);
  private static volatile boolean threadAllocationEnabled =
      enableThreadAllocationIfSupported(THREAD_MX_BEAN, THREAD_ALLOC_SUPPORTED);

  private ThreadAllocationMetrics() {}

  static long currentThreadAllocatedBytes() {
    if (!threadAllocationEnabled || THREAD_MX_BEAN == null) return -1L;
    try {
      long allocatedBytes = THREAD_MX_BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
      return allocatedBytes >= 0L ? allocatedBytes : -1L;
    } catch (Throwable ignored) {
      threadAllocationEnabled = false;
      return -1L;
    }
  }

  private static com.sun.management.ThreadMXBean resolveThreadMxBean() {
    try {
      java.lang.management.ThreadMXBean genericBean = ManagementFactory.getThreadMXBean();
      if (genericBean instanceof com.sun.management.ThreadMXBean threadMxBean) {
        return threadMxBean;
      }
      return null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static boolean supportsThreadAllocation(com.sun.management.ThreadMXBean threadMxBean) {
    if (threadMxBean == null) return false;
    try {
      return threadMxBean.isThreadAllocatedMemorySupported();
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static boolean enableThreadAllocationIfSupported(
      com.sun.management.ThreadMXBean threadMxBean,
      boolean threadAllocationSupported
  ) {
    if (!threadAllocationSupported || threadMxBean == null) return false;
    try {
      if (!threadMxBean.isThreadAllocatedMemoryEnabled()) {
        threadMxBean.setThreadAllocatedMemoryEnabled(true);
      }
      return threadMxBean.isThreadAllocatedMemoryEnabled();
    } catch (Throwable ignored) {
      return false;
    }
  }
}
