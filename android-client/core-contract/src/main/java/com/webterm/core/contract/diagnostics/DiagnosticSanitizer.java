package com.webterm.core.contract.diagnostics;

import java.util.HashSet;
import java.util.Set;

public final class DiagnosticSanitizer {
    private static final Set<String> SENSITIVE_WORDS = new HashSet<>();

    static {
        SENSITIVE_WORDS.add("authorization");
        SENSITIVE_WORDS.add("cookie");
        SENSITIVE_WORDS.add("setcookie");
        SENSITIVE_WORDS.add("password");
        SENSITIVE_WORDS.add("passwd");
        SENSITIVE_WORDS.add("otp");
        SENSITIVE_WORDS.add("secret");
        SENSITIVE_WORDS.add("token");
        SENSITIVE_WORDS.add("accesstoken");
        SENSITIVE_WORDS.add("refreshtoken");
        SENSITIVE_WORDS.add("clipboard");
        SENSITIVE_WORDS.add("prompt");
        SENSITIVE_WORDS.add("command");
        SENSITIVE_WORDS.add("body");
        SENSITIVE_WORDS.add("content");
        SENSITIVE_WORDS.add("terminaltext");
        SENSITIVE_WORDS.add("inputtext");
    }

    private DiagnosticSanitizer() {}

    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").replace("-", "").toLowerCase();
        return SENSITIVE_WORDS.contains(normalized);
    }
}
