package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.action.classmethods;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceFile;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.discovery.JavaSourceIndex;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Selects exact class matches in deterministic source order. */
public class ClassMethodsClassSelector {

    /** Selects files matching either the simple or fully qualified class hint. */
    public List<JavaSourceFile> select(JavaSourceIndex index, String classHint) {
        String needle = classHint.trim().toLowerCase(Locale.ROOT);
        return index.files().stream()
                .filter(file -> {
                    String fqcn = file.fqcn() == null ? "" : file.fqcn().toLowerCase(Locale.ROOT);
                    String className = file.className() == null
                            ? "" : file.className().toLowerCase(Locale.ROOT);
                    return needle.equals(fqcn) || needle.equals(className);
                })
                .sorted(Comparator.comparing((JavaSourceFile file) -> file.fqcn() == null
                        ? "" : file.fqcn().toLowerCase(Locale.ROOT))
                        .thenComparing(file -> file.file().toString()))
                .toList();
    }
}
