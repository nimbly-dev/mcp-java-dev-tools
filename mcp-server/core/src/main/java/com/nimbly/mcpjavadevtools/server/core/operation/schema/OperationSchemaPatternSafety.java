package com.nimbly.mcpjavadevtools.server.core.operation.schema;

/** Restricts schema patterns to a linear-time subset before runtime matching. */
public class OperationSchemaPatternSafety {

    private OperationSchemaPatternSafety() {
    }

    static void validate(String pattern) {
        boolean escaped = false, inCharacterClass = false;
        int quantifierCount = 0;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (escaped) {
                if (Character.isDigit(current)) {
                    throw new IllegalArgumentException(
                            "operation schema pattern backreferences are unsupported");
                }
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[') {
                inCharacterClass = true;
                continue;
            }
            if (current == ']' && inCharacterClass) {
                inCharacterClass = false;
                continue;
            }
            if (inCharacterClass) {
                continue;
            }
            if (current == '(' || current == ')' || current == '|') {
                throw new IllegalArgumentException(
                        "operation schema pattern grouping and alternation are unsupported");
            }
            if (isQuantifier(current)) {
                quantifierCount++;
                if (quantifierCount > 1) {
                    throw new IllegalArgumentException(
                            "operation schema pattern has too many quantifiers");
                }
            }
            if (isQuantifier(current) && index > 0 && isQuantifier(pattern.charAt(index - 1))) {
                throw new IllegalArgumentException(
                        "operation schema adjacent quantifiers are unsupported");
            }
        }
    }

    private static boolean isQuantifier(char value) {
        return value == '*' || value == '+' || value == '?' || value == '{';
    }
}
