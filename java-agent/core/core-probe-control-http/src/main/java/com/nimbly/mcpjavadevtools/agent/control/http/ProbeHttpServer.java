package com.nimbly.mcpjavadevtools.agent.control.http;

import com.nimbly.mcpjavadevtools.agent.control.http.actuation.ProbeActuationHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.capture.ProbeCaptureHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.correlation.ProbeCorrelationConfigureHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.correlation.ProbeCorrelationEventsHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.correlation.ProbeCorrelationStatusHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.failure.ProbeFailureAnalyzeHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.failure.ProbeFailureVerifyHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.profiler.ProbeProfilerHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.reset.ProbeResetHttpHandler;
import com.nimbly.mcpjavadevtools.agent.control.http.status.ProbeStatusHttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public final class ProbeHttpServer {
  private final HttpServer server;

  private ProbeHttpServer(HttpServer server) {
    this.server = server;
  }

  public static ProbeHttpServer start(String host, int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 16);
    server.createContext("/__probe/status", new ProbeStatusHttpHandler());
    server.createContext("/__probe/correlation/events", new ProbeCorrelationEventsHttpHandler());
    server.createContext("/__probe/correlation/status", new ProbeCorrelationStatusHttpHandler());
    server.createContext("/__probe/correlation/configure", new ProbeCorrelationConfigureHttpHandler());
    server.createContext("/__probe/reset", new ProbeResetHttpHandler());
    server.createContext("/__probe/actuate", new ProbeActuationHttpHandler());
    server.createContext("/__probe/capture", new ProbeCaptureHttpHandler());
    server.createContext("/__probe/failure/analyze", new ProbeFailureAnalyzeHttpHandler());
    server.createContext("/__probe/failure/verify", new ProbeFailureVerifyHttpHandler());
    server.createContext("/__probe/profiler", new ProbeProfilerHttpHandler());
    server.setExecutor(null);
    server.start();
    return new ProbeHttpServer(server);
  }

  int port() {
    return server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
  }

  public HttpServer rawServer() {
    return server;
  }
}
