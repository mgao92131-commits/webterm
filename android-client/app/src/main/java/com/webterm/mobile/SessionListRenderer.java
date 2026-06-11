package com.webterm.mobile;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

final class SessionListRenderer {
    private final Activity activity;
    private final SessionRowActions actions;

    SessionListRenderer(Activity activity, SessionRowActions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    void render(ServerConfig server, JSONArray sessions, LinearLayout subList, CleanupCallback cleanupCallback) {
        subList.setTag("online_list");
        java.util.Set<String> newIds = new java.util.HashSet<>();
        java.util.Set<String> liveIdentities = new java.util.HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String id = session.optString("id");
            newIds.add(id);
            String identity = SessionIdentity.value(id, session.optString("instanceId", ""), session.optString("createdAt", ""));
            if (!identity.isEmpty()) liveIdentities.add(identity);
        }
        cleanupCallback.removeMissingCachedSessions(server.url, liveIdentities);

        removeStaleRows(subList, newIds);

        if (sessions.length() == 0) {
            subList.removeAllViews();
            subList.addView(SessionListItemViews.emptyItem(activity));
            return;
        }

        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String id = session.optString("id");
            View existingRow = findRow(subList, id);
            if (existingRow != null) {
                SessionRowHelper.updateSessionRow(actions, existingRow, session, server);
            } else {
                SessionRowHelper.addSessionRow(activity, actions, session, server, subList);
            }
        }
    }

    private void removeStaleRows(LinearLayout subList, java.util.Set<String> newIds) {
        for (int i = subList.getChildCount() - 1; i >= 0; i--) {
            View child = subList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && !newIds.contains((String) tag)) {
                subList.removeViewAt(i);
            } else if ("empty_item".equals(tag) || "error_item".equals(tag)) {
                subList.removeViewAt(i);
            }
        }
    }

    private View findRow(LinearLayout subList, String id) {
        for (int i = 0; i < subList.getChildCount(); i++) {
            View child = subList.getChildAt(i);
            if (id.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    interface CleanupCallback {
        void removeMissingCachedSessions(String baseUrl, java.util.Set<String> liveSessionIdentities);
    }
}
