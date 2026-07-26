package com.webterm.feature.home;

import com.webterm.core.api.SessionIdentity;

import org.json.JSONObject;

/**
 * 会话列表目录分组与组内会话的确定性排序。
 * 不依赖服务端数组顺序、HashMap 迭代顺序或组内会话时间推导的组序。
 */
final class SessionListOrdering {

    private SessionListOrdering() {
    }

    /** 去掉首尾空白与末尾路径分隔符；空串表示未同步目录。 */
    static String normalizedCwd(String cwd) {
        String value = String.valueOf(cwd == null ? "" : cwd).trim();
        if (value.isEmpty()) return "";
        while (value.length() > 1 && (value.endsWith("/") || value.endsWith("\\"))) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 目录分组比较：空 cwd（未同步）固定最后；其余先忽略大小写，再大小写敏感。
     */
    static int compareDirectoryCwds(String firstCwd, String secondCwd) {
        String first = firstCwd == null ? "" : firstCwd;
        String second = secondCwd == null ? "" : secondCwd;
        boolean firstUnknown = first.isEmpty();
        boolean secondUnknown = second.isEmpty();
        if (firstUnknown != secondUnknown) {
            return firstUnknown ? 1 : -1;
        }
        int ignoreCase = first.compareToIgnoreCase(second);
        if (ignoreCase != 0) return ignoreCase;
        return first.compareTo(second);
    }

    /**
     * 组内会话：有 createdAt 的优先，且按时间倒序；无时间的在后；同档用 Session Identity。
     */
    static int compareSessionOrder(JSONObject first, JSONObject second) {
        boolean firstHasTime = hasCreatedAt(first);
        boolean secondHasTime = hasCreatedAt(second);
        if (firstHasTime != secondHasTime) {
            return firstHasTime ? -1 : 1;
        }
        if (firstHasTime) {
            int byTime = createdAt(second).compareTo(createdAt(first));
            if (byTime != 0) return byTime;
        }
        return sessionIdentity(first).compareTo(sessionIdentity(second));
    }

    static boolean hasCreatedAt(JSONObject session) {
        return !createdAt(session).isEmpty();
    }

    static String createdAt(JSONObject session) {
        if (session == null) return "";
        return session.optString("createdAt", "").trim();
    }

    static String sessionIdentity(JSONObject session) {
        if (session == null) return "id:";
        String id = session.optString("id");
        String identity = SessionIdentity.value(
            id,
            session.optString("instanceId", ""),
            session.optString("createdAt", "")
        );
        return identity.isEmpty() ? "id:" + id : identity;
    }

    static String sessionRowKey(JSONObject session) {
        return "session:" + sessionIdentity(session);
    }
}
