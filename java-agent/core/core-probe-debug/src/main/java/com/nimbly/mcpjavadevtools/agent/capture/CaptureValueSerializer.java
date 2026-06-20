package com.nimbly.mcpjavadevtools.agent.capture;

import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class CaptureValueSerializer {
  private static final Pattern SENSITIVE_NAME_PATTERN =
      Pattern.compile(
          "(?i).*(password|passwd|pwd|secret|token|authorization|cookie|api[-_]?key|session).*",
          Pattern.CASE_INSENSITIVE
      );
  private static final Pattern SECRET_VALUE_PATTERN =
      Pattern.compile(
          "(?i)^(bearer\\s+.+|basic\\s+.+|[A-Za-z0-9_-]{24,}\\.[A-Za-z0-9_-]{24,}\\.[A-Za-z0-9_-]{24,})$"
      );

  private CaptureValueSerializer() {}

  static List<CaptureValue> serializeArguments(
      Object[] allArguments,
      int captureMaxArgs,
      String captureRedactionMode,
      int captureStoredMaxChars
  ) {
    if (allArguments == null || allArguments.length == 0) return Collections.emptyList();
    int max = Math.min(allArguments.length, captureMaxArgs);
    List<CaptureValue> out = new ArrayList<>();
    for (int i = 0; i < max; i++) {
      out.add(serializeSingleValue(allArguments[i], null, captureRedactionMode, captureStoredMaxChars));
    }
    if (allArguments.length > max) {
      out.add(
          CaptureValue.synthetic(
              quoteJson("<args_truncated:" + allArguments.length + " total, kept " + max + ">"),
              true,
              false
          )
      );
    }
    return out;
  }

  static CaptureValue serializeSingleValue(
      Object value,
      String keyHint,
      String captureRedactionMode,
      int captureStoredMaxChars
  ) {
    ValueNormalization normalized =
        normalizeValue(value, captureRedactionMode, keyHint, 4, 24, 24, new IdentityHashMap<>());
    return CaptureValue.fromNormalized(normalized.value, normalized.redacted, captureStoredMaxChars);
  }

  private static ValueNormalization normalizeValue(
      Object value,
      String redactionMode,
      String keyHint,
      int depth,
      int maxItems,
      int maxFields,
      IdentityHashMap<Object, Boolean> seen
  ) {
    if (shouldRedactByName(redactionMode, keyHint)) {
      return new ValueNormalization(quoteJson("<redacted>"), true);
    }
    if (value == null) return new ValueNormalization("null", false);

    Class<?> cls = value.getClass();
    if (depth <= 0) {
      return new ValueNormalization(quoteJson("<max_depth:" + cls.getName() + ">"), false);
    }

    if (value instanceof CharSequence) {
      String text = value.toString();
      if (shouldRedactByValue(redactionMode, text)) {
        return new ValueNormalization(quoteJson("<redacted>"), true);
      }
      return new ValueNormalization(quoteJson(text), false);
    }
    if (value instanceof Number || value instanceof Boolean) {
      return new ValueNormalization(String.valueOf(value), false);
    }
    if (value instanceof Character) {
      return new ValueNormalization(quoteJson(String.valueOf(value)), false);
    }
    if (value instanceof Enum<?>) {
      return new ValueNormalization(quoteJson(((Enum<?>) value).name()), false);
    }
    if (value instanceof Class<?>) {
      return new ValueNormalization(quoteJson(((Class<?>) value).getName()), false);
    }

    if (seen.containsKey(value)) {
      return new ValueNormalization(quoteJson("<cycle:" + cls.getName() + ">"), false);
    }
    seen.put(value, Boolean.TRUE);

    try {
      if (cls.isArray()) {
        int len = Array.getLength(value);
        List<String> items = new ArrayList<>();
        boolean redacted = false;
        int limit = Math.min(len, maxItems);
        for (int i = 0; i < limit; i++) {
          ValueNormalization child =
              normalizeValue(Array.get(value, i), redactionMode, null, depth - 1, maxItems, maxFields, seen);
          items.add(child.value);
          redacted = redacted || child.redacted;
        }
        if (len > limit) {
          items.add(quoteJson("<items_truncated:" + len + " total, kept " + limit + ">"));
        }
        return new ValueNormalization("[" + String.join(",", items) + "]", redacted);
      }

      if (value instanceof Iterable<?>) {
        List<String> items = new ArrayList<>();
        boolean redacted = false;
        int idx = 0;
        for (Object item : (Iterable<?>) value) {
          if (idx >= maxItems) {
            items.add(quoteJson("<items_truncated:kept " + maxItems + ">"));
            break;
          }
          ValueNormalization child =
              normalizeValue(item, redactionMode, null, depth - 1, maxItems, maxFields, seen);
          items.add(child.value);
          redacted = redacted || child.redacted;
          idx++;
        }
        return new ValueNormalization("[" + String.join(",", items) + "]", redacted);
      }

      if (value instanceof Map<?, ?>) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(((Map<?, ?>) value).entrySet());
        entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey())));
        List<String> out = new ArrayList<>();
        boolean redacted = false;
        int limit = Math.min(entries.size(), maxItems);
        for (int i = 0; i < limit; i++) {
          Map.Entry<?, ?> e = entries.get(i);
          String name = String.valueOf(e.getKey());
          ValueNormalization child =
              normalizeValue(e.getValue(), redactionMode, name, depth - 1, maxItems, maxFields, seen);
          out.add(quoteJson(name) + ":" + child.value);
          redacted = redacted || child.redacted;
        }
        if (entries.size() > limit) {
          out.add(quoteJson("<entries_truncated>") + ":" + quoteJson(String.valueOf(entries.size())));
        }
        return new ValueNormalization("{" + String.join(",", out) + "}", redacted);
      }

      if (value instanceof Throwable) {
        Throwable t = (Throwable) value;
        String message = t.getMessage() == null ? "" : t.getMessage();
        if (shouldRedactByValue(redactionMode, message)) {
          message = "<redacted>";
        }
        String payload =
            "{"
                + quoteJson("type")
                + ":"
                + quoteJson(t.getClass().getName())
                + ","
                + quoteJson("message")
                + ":"
                + quoteJson(message)
                + "}";
        return new ValueNormalization(payload, shouldRedactByValue(redactionMode, message));
      }

      List<Field> fields = collectInstanceFields(cls);
      fields.sort(Comparator.comparing(Field::getName));
      int limit = Math.min(fields.size(), maxFields);
      List<String> out = new ArrayList<>();
      boolean redacted = false;
      for (int i = 0; i < limit; i++) {
        Field f = fields.get(i);
        String name = f.getName();
        try {
          f.setAccessible(true);
          Object fieldValue = f.get(value);
          ValueNormalization child =
              normalizeValue(fieldValue, redactionMode, name, depth - 1, maxItems, maxFields, seen);
          out.add(quoteJson(name) + ":" + child.value);
          redacted = redacted || child.redacted;
        } catch (Throwable err) {
          out.add(quoteJson(name) + ":" + quoteJson("<inaccessible:" + err.getClass().getSimpleName() + ">"));
        }
      }
      if (fields.size() > limit) {
        out.add(quoteJson("<fields_truncated>") + ":" + quoteJson(String.valueOf(fields.size())));
      }
      String payload =
          "{"
              + quoteJson("@type")
              + ":"
              + quoteJson(cls.getName())
              + (out.isEmpty() ? "" : "," + String.join(",", out))
              + "}";
      return new ValueNormalization(payload, redacted);
    } finally {
      seen.remove(value);
    }
  }

  private static List<Field> collectInstanceFields(Class<?> cls) {
    List<Field> out = new ArrayList<>();
    Class<?> cursor = cls;
    int depth = 0;
    while (cursor != null && cursor != Object.class && depth < 6) {
      Field[] fields = cursor.getDeclaredFields();
      for (Field field : fields) {
        int mod = field.getModifiers();
        if (Modifier.isStatic(mod)) continue;
        out.add(field);
      }
      cursor = cursor.getSuperclass();
      depth++;
    }
    return out;
  }

  private static boolean shouldRedactByName(String redactionMode, String keyHint) {
    if (!"basic".equals(redactionMode)) return false;
    if (keyHint == null || keyHint.isBlank()) return false;
    return SENSITIVE_NAME_PATTERN.matcher(keyHint).matches();
  }

  private static boolean shouldRedactByValue(String redactionMode, String value) {
    if (!"basic".equals(redactionMode)) return false;
    if (value == null || value.isBlank()) return false;
    String normalized = value.trim();
    if (normalized.length() >= 64 && normalized.indexOf(' ') < 0) {
      return true;
    }
    return SECRET_VALUE_PATTERN.matcher(normalized).matches();
  }

  private static String quoteJson(String raw) {
    return "\"" + ProbeRuntime.escJson(raw == null ? "" : raw) + "\"";
  }

  private static final class ValueNormalization {
    private final String value;
    private final boolean redacted;

    private ValueNormalization(String value, boolean redacted) {
      this.value = value;
      this.redacted = redacted;
    }
  }
}

