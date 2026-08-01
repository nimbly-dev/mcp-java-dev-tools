package com.nimbly.mcpjavadevtools.agent.control.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class ProbeHttpJson {
  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      .build();

  private ProbeHttpJson() {}

  public static String queryParam(URI uri, String key) {
    String q = uri.getRawQuery();
    if (q == null || q.isEmpty()) return null;
    Map<String, String> m = parseQuery(q);
    return m.get(key);
  }

  public static <T> T readBodyJson(InputStream body, Class<T> type) throws IOException {
    byte[] bytes = body.readAllBytes();
    if (bytes.length == 0) {
      return MAPPER.readValue("{}", type);
    }
    return MAPPER.readValue(bytes, type);
  }

  public static List<String> normalizeDistinctKeys(List<String> keys) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String raw : keys) {
      if (raw == null) continue;
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) continue;
      out.add(trimmed);
    }
    return new ArrayList<>(out);
  }

  public static String toJson(Object payload) throws IOException {
    return MAPPER.writeValueAsString(payload);
  }

  public static void writeJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
    writeJsonRaw(exchange, statusCode, toJson(payload));
  }

  public static void writeJsonRaw(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static Map<String, String> parseQuery(String q) {
    Map<String, String> out = new HashMap<>();
    String[] parts = q.split("&");
    for (String p : parts) {
      int eq = p.indexOf('=');
      if (eq <= 0) continue;
      String k = urlDecode(p.substring(0, eq));
      String v = urlDecode(p.substring(eq + 1));
      out.put(k, v);
    }
    return out;
  }

  private static String urlDecode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }
}
