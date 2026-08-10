package com.nimbly.mcpjavadevtools.server;

import com.nimbly.mcpjavadevtools.server.lifecycle.StartupFailureReporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpJavaDevToolsServerApplication {

    public static void main(String[] arguments) {
        SpringApplication application = new SpringApplication(McpJavaDevToolsServerApplication.class);
        application.setLogStartupInfo(false);
        try {
            application.run(arguments);
        } catch (RuntimeException exception) {
            StartupFailureReporter.report(System.err);
            System.exit(1);
        }
    }
}
