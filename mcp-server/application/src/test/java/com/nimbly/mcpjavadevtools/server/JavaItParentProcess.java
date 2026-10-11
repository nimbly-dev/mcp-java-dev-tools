package com.nimbly.mcpjavadevtools.server;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;

/** Small subprocess entry point for real parent-exit and descendant-cleanup assertions. */
public class JavaItParentProcess {
    public static void main(String[] arguments) throws Exception {
        if ("sleep".equals(arguments[0])) {
            Thread.sleep(120_000);
            return;
        }
        if ("stdin-holder".equals(arguments[0])) {
            try (Socket input = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(arguments[1]))) {
                input.getInputStream().transferTo(System.out);
            }
            return;
        }
        ProcessBuilder builder;
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        if ("server".equals(arguments[0])) {
            builder = new ProcessBuilder(java, "-jar", arguments[1]);
        } else {
            builder = new ProcessBuilder(java, "-cp",
                    arguments[1], JavaItParentProcess.class.getName(), "sleep");
        }
        builder.directory(Path.of(arguments[2]).toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        startAndReportChild(arguments, java, builder);
        // The holder owns the pipeline writer independently of this parent process.
        System.in.read();
        Runtime.getRuntime().halt(0);
    }

    private static void startAndReportChild(String[] arguments, String java, ProcessBuilder builder) throws Exception {
        Process child;
        Process holder = null;
        if ("server".equals(arguments[0])) {
            ProcessBuilder input = new ProcessBuilder(java, "-cp", arguments[4],
                    JavaItParentProcess.class.getName(), "stdin-holder", arguments[3])
                    .directory(Path.of(arguments[2]).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            List<Process> pipeline = ProcessBuilder.startPipeline(List.of(input, builder));
            holder = pipeline.get(0);
            child = pipeline.get(1);
        } else {
            child = builder.start();
        }
        System.out.println(child.pid());
        if (holder != null) {
            System.out.println(holder.pid());
        }
        System.out.flush();
    }
}
