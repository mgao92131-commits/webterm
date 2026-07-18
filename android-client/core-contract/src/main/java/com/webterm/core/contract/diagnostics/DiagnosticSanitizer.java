package com.webterm.core.contract.diagnostics;

public final class DiagnosticSanitizer {
    private DiagnosticSanitizer() {}

    public static boolean isSensitive(String key) {
        String normalized = normalize(key);
        return normalized.contains("authorization")
            || normalized.contains("cookie")
            || normalized.contains("password")
            || normalized.contains("passwd")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.equals("otp")
            || normalized.contains("clipboard")
            || normalized.contains("prompt")
            || normalized.contains("command")
            || normalized.contains("body")
            || normalized.contains("content")
            || normalized.contains("terminaltext")
            || normalized.contains("inputtext");
    }

    private static String normalize(String key) {
        if (key == null) return "";
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
