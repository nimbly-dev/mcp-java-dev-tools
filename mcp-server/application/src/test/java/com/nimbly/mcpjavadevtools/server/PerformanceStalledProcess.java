package com.nimbly.mcpjavadevtools.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Negative prerequisite: a real stalled process tree, never a successful workload provider. */
public class PerformanceStalledProcess {
    public static void main(String[] arguments) throws Exception {
        Path marker = Path.of(arguments[0]);
        if (arguments.length == 1 || arguments[1].equals("exit")) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                    PerformanceStalledProcess.class.getName(), marker.toString(), "child")).start();
            Files.writeString(marker, ProcessHandle.current().pid() + "\n" + child.pid() + "\n");
            if (arguments.length > 1) {
                System.exit(Integer.parseInt(arguments[2]));
            }
        }
        Thread.sleep(120_000);
    }
}
