package com.nimbly.mcpjavadevtools.server.lifecycle;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class ParentProcessLifecycle implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final long CHECK_INTERVAL_SECONDS = 2;

    private final ConfigurableApplicationContext applicationContext;
    private ScheduledExecutorService monitor;

    public ParentProcessLifecycle(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        parent.ifPresent(this::monitorParent);
    }

    @Override
    public void destroy() {
        if (monitor != null) {
            monitor.shutdownNow();
        }
    }

    private void monitorParent(ProcessHandle parent) {
        ParentProcessWatcher watcher = new ParentProcessWatcher(
                parent.pid(),
                processId -> ProcessHandle.of(processId).isPresent(),
                applicationContext::close);
        monitor = Executors.newSingleThreadScheduledExecutor(this::newMonitorThread);
        monitor.scheduleWithFixedDelay(
                watcher::checkParent,
                CHECK_INTERVAL_SECONDS,
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    private Thread newMonitorThread(Runnable task) {
        Thread thread = new Thread(task, "mcp-parent-process-monitor");
        thread.setDaemon(true);
        return thread;
    }
}
