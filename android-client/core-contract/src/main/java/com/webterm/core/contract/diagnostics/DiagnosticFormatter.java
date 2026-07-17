package com.webterm.core.contract.diagnostics;

import java.util.Map;
import java.util.TreeMap;

public final class DiagnosticFormatter {

    private DiagnosticFormatter() {}

    public static String format(String area, String event, Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("area=").append(formatValue("area", area));
        sb.append(" event=").append(formatValue("event", event));

        if (fields != null && !fields.isEmpty()) {
            Map<String, ?> sortedFields = new TreeMap<>(fields);
            for (Map.Entry<String, ?> entry : sortedFields.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                sb.append(" ").append(key).append("=").append(formatValue(key, val));
            }
        }
        return sb.toString();
    }

    private static String formatValue(String key, Object value) {
        if (DiagnosticSanitizer.isSensitive(key)) {
            return "[REDACTED]";
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            String str = (String) value;
            str = escapeString(str);
            if (str.length() > 256) {
                str = str.substring(0, 256) + "...[truncated]";
            }
            return str;
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        } else {
            String simpleName = value.getClass().getSimpleName();
            if (simpleName.isEmpty()) {
                simpleName = value.getClass().getName();
                int lastDot = simpleName.lastIndexOf('.');
                if (lastDot >= 0) {
                    simpleName = simpleName.substring(lastDot + 1);
                }
            }
            return "[unsupported:" + simpleName + "]";
        }
    }

    private static String escapeString(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
