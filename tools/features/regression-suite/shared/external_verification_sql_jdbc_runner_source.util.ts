export const JDBC_SQL_RUNNER_SOURCE = String.raw`
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class JdbcSqlRunner {
  private static final Base64.Decoder BASE64 = Base64.getDecoder();

  private record BindingValue(String type, Object value) {}
  private record ParsedStatement(String sql, List<String> orderedNames) {}

  public static void main(String[] args) throws Exception {
    long startedAt = System.currentTimeMillis();
    try {
      String jdbcUrl = requiredEnv("SQL_JDBC_URL");
      String statement = decodeBase64(requiredEnv("SQL_STATEMENT_B64"));
      String driverClass = optionalEnv("SQL_DRIVER_CLASS");
      int timeoutSeconds = parseTimeoutSeconds(optionalEnv("SQL_TIMEOUT_SECONDS"));
      Map<String, BindingValue> bindings = readBindings();
      Properties properties = readConnectionProperties();

      if (driverClass != null && !driverClass.isBlank()) {
        Class.forName(driverClass.trim());
      }

      ParsedStatement parsed = rewriteNamedParameters(statement, bindings);
      try (Connection connection = DriverManager.getConnection(jdbcUrl, properties);
           PreparedStatement prepared = connection.prepareStatement(parsed.sql())) {
        if (timeoutSeconds > 0) {
          prepared.setQueryTimeout(timeoutSeconds);
        }
        bindParameters(prepared, parsed.orderedNames(), bindings);
        try (ResultSet resultSet = prepared.executeQuery()) {
          emitSuccess(startedAt, resultSet);
        }
      }
    } catch (Throwable error) {
      emitError(startedAt, error.getMessage() == null ? error.toString() : error.getMessage());
    }
  }

  private static void emitSuccess(long startedAt, ResultSet resultSet) throws SQLException {
    long durationMs = System.currentTimeMillis() - startedAt;
    ResultSetMetaData meta = resultSet.getMetaData();
    int columnCount = meta.getColumnCount();
    int rowIndex = 0;
    System.out.println("OK\t" + durationMs);
    while (resultSet.next()) {
      for (int columnIndex = 1; columnIndex <= columnCount; columnIndex += 1) {
        String label = meta.getColumnLabel(columnIndex);
        if (label == null || label.isBlank()) {
          label = meta.getColumnName(columnIndex);
        }
        EncodedCell encoded = encodeCell(resultSet.getObject(columnIndex));
        System.out.println(
          "ROW\t" +
          rowIndex + "\t" +
          encodeBase64(label) + "\t" +
          encoded.type + "\t" +
          encodeBase64(encoded.value)
        );
      }
      rowIndex += 1;
    }
  }

  private static void emitError(long startedAt, String message) {
    long durationMs = System.currentTimeMillis() - startedAt;
    System.out.println("ERR\t" + durationMs + "\t" + encodeBase64(message));
  }

  private static Map<String, BindingValue> readBindings() {
    Map<String, BindingValue> bindings = new LinkedHashMap<>();
    int count = parseCount(optionalEnv("SQL_BINDING_COUNT"));
    for (int index = 0; index < count; index += 1) {
      String prefix = "SQL_BINDING_" + index + "_";
      String name = requiredEnv(prefix + "NAME");
      String type = requiredEnv(prefix + "TYPE");
      String encodedValue = optionalEnv(prefix + "VALUE_B64");
      bindings.put(name, new BindingValue(type, decodeBindingValue(type, encodedValue)));
    }
    return bindings;
  }

  private static Properties readConnectionProperties() {
    Properties properties = new Properties();
    int count = parseCount(optionalEnv("SQL_PROP_COUNT"));
    for (int index = 0; index < count; index += 1) {
      String key = requiredEnv("SQL_PROP_" + index + "_KEY");
      String value = decodeBase64(requiredEnv("SQL_PROP_" + index + "_VALUE_B64"));
      properties.setProperty(key, value);
    }
    return properties;
  }

  private static ParsedStatement rewriteNamedParameters(String sql, Map<String, BindingValue> bindings) {
    StringBuilder rewritten = new StringBuilder();
    List<String> orderedNames = new ArrayList<>();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    for (int index = 0; index < sql.length(); index += 1) {
      char current = sql.charAt(index);
      if (current == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
        rewritten.append(current);
        continue;
      }
      if (current == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
        rewritten.append(current);
        continue;
      }
      if (current == ':' && !inSingleQuote && !inDoubleQuote) {
        if (index + 1 < sql.length() && sql.charAt(index + 1) == ':') {
          rewritten.append("::");
          index += 1;
          continue;
        }
        int scan = index + 1;
        while (scan < sql.length()) {
          char candidate = sql.charAt(scan);
          if (Character.isLetterOrDigit(candidate) || candidate == '_') {
            scan += 1;
            continue;
          }
          break;
        }
        if (scan == index + 1) {
          rewritten.append(current);
          continue;
        }
        String bindingName = sql.substring(index + 1, scan);
        if (!bindings.containsKey(bindingName)) {
          throw new IllegalArgumentException("missing_sql_binding:" + bindingName);
        }
        orderedNames.add(bindingName);
        rewritten.append('?');
        index = scan - 1;
        continue;
      }
      rewritten.append(current);
    }
    return new ParsedStatement(rewritten.toString(), orderedNames);
  }

  private static void bindParameters(
    PreparedStatement prepared,
    List<String> orderedNames,
    Map<String, BindingValue> bindings
  ) throws SQLException {
    for (int index = 0; index < orderedNames.size(); index += 1) {
      String bindingName = orderedNames.get(index);
      BindingValue binding = bindings.get(bindingName);
      if (binding == null) {
        throw new IllegalArgumentException("missing_sql_binding:" + bindingName);
      }
      int parameterIndex = index + 1;
      switch (binding.type) {
        case "null" -> prepared.setObject(parameterIndex, null);
        case "string" -> prepared.setString(parameterIndex, (String) binding.value);
        case "number" -> prepared.setDouble(parameterIndex, ((Number) binding.value).doubleValue());
        case "bigint" -> prepared.setString(parameterIndex, binding.value.toString());
        case "bytes" -> prepared.setBytes(parameterIndex, (byte[]) binding.value);
        default -> throw new IllegalArgumentException("unsupported_binding_type:" + binding.type);
      }
    }
  }

  private record EncodedCell(String type, String value) {}

  private static EncodedCell encodeCell(Object value) {
    if (value == null) {
      return new EncodedCell("null", "");
    }
    if (value instanceof byte[] bytes) {
      return new EncodedCell("bytes", Base64.getEncoder().encodeToString(bytes));
    }
    if (value instanceof Boolean bool) {
      return new EncodedCell("boolean", bool.toString());
    }
    if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
      return new EncodedCell("integer", value.toString());
    }
    if (value instanceof Float || value instanceof Double) {
      return new EncodedCell("double", value.toString());
    }
    if (value instanceof BigInteger) {
      return new EncodedCell("bigint", value.toString());
    }
    if (value instanceof BigDecimal) {
      return new EncodedCell("decimal", ((BigDecimal) value).toPlainString());
    }
    if (value instanceof Number number) {
      return new EncodedCell("double", number.toString());
    }
    if (value instanceof java.sql.Timestamp || value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof TemporalAccessor) {
      return new EncodedCell("string", value.toString());
    }
    return new EncodedCell("string", value.toString());
  }

  private static Object decodeBindingValue(String type, String encodedValue) {
    return switch (type) {
      case "null" -> null;
      case "string" -> decodeBase64(requiredValue(encodedValue, type));
      case "number" -> Double.valueOf(decodeBase64(requiredValue(encodedValue, type)));
      case "bigint" -> decodeBase64(requiredValue(encodedValue, type));
      case "bytes" -> BASE64.decode(requiredValue(encodedValue, type));
      default -> throw new IllegalArgumentException("unsupported_binding_type:" + type);
    };
  }

  private static String requiredValue(String value, String type) {
    if (value == null) {
      throw new IllegalArgumentException("missing_binding_value:" + type);
    }
    return value;
  }

  private static int parseTimeoutSeconds(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    int timeoutMs = Integer.parseInt(value);
    if (timeoutMs <= 0) {
      return 0;
    }
    return Math.max(1, (int) Math.ceil(timeoutMs / 1000.0d));
  }

  private static int parseCount(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    return Integer.parseInt(value);
  }

  private static String requiredEnv(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing_env:" + key);
    }
    return value;
  }

  private static String optionalEnv(String key) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? null : value;
  }

  private static String decodeBase64(String value) {
    return new String(BASE64.decode(value), StandardCharsets.UTF_8);
  }

  private static String encodeBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
`;
