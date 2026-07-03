package com.webterm.mobile.ui.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.webterm.core.cache.TerminalCacheScope;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.session.SessionIdentity;
import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.UIUtils;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SESSION = 1;
    private static final int TYPE_EMPTY = 2;
    private static final int TYPE_ERROR = 3;
    private static final int TYPE_GROUP_HEADER = 4;

    private final Activity activity;
    private final SessionRowActions actions;
    private final Runnable onRetry;
    private final List<RowItem> items = new ArrayList<>();
    private CollapseState collapseState;
    private ServerConfig server;
    private String errorMessage;
    private JSONArray lastSessions;

    public SessionRecyclerAdapter(Activity activity, SessionRowActions actions, Runnable onRetry) {
        this.activity = activity;
        this.actions = actions;
        this.onRetry = onRetry;
        setHasStableIds(true);
        showEmpty();
    }

    public void setCollapseState(CollapseState collapseState) {
        this.collapseState = collapseState;
    }

    public void submitSessions(ServerConfig server, JSONArray sessions) {
        this.server = server;
        this.errorMessage = null;
        this.lastSessions = copySessions(sessions);
        List<RowItem> next = buildGroupedItems(server, sessions);
        if (next.isEmpty()) next.add(RowItem.empty());
        updateItems(next);
    }

    public void showError(String message) {
        errorMessage = message == null || message.isEmpty() ? "连接失败" : message;
        lastSessions = null;
        List<RowItem> next = new ArrayList<>();
        next.add(RowItem.error(errorMessage));
        updateItems(next);
    }

    public void showEmpty() {
        errorMessage = null;
        lastSessions = null;
        List<RowItem> next = new ArrayList<>();
        next.add(RowItem.empty());
        updateItems(next);
    }

    public boolean hasSessionRows() {
        if (lastSessions != null && lastSessions.length() > 0) return true;
        for (RowItem item : items) {
            if (item.type == TYPE_SESSION) return true;
        }
        return false;
    }

    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (removeFromLastSessions(sessionId)) {
            List<RowItem> next = buildGroupedItems(server, lastSessions);
            if (next.isEmpty()) next.add(RowItem.empty());
            updateItems(next);
            return;
        }
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            RowItem item = items.get(i);
            if (item.type == TYPE_SESSION && sessionId.equals(item.id)) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        RowItem removed = items.get(index);
        items.remove(index);
        notifyItemRemoved(index);
        if (removed.groupKey != null && !hasSessionRowsInGroup(removed.groupKey)) {
            int headerIndex = findGroupHeaderIndex(removed.groupKey);
            if (headerIndex >= 0) {
                items.remove(headerIndex);
                notifyItemRemoved(headerIndex);
            }
            setGroupCollapsed(removed.groupKey, false);
        }
        if (!hasSessionRows()) {
            items.add(RowItem.empty());
            notifyItemInserted(items.size() - 1);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).stableId();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_GROUP_HEADER) {
            View header = createGroupHeaderView(parent);
            header.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerView.ViewHolder(header) {};
        }
        if (viewType == TYPE_SESSION) {
            View row = SessionRowHelper.createSessionRowView(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, UIUtils.dp(parent.getContext(), 12));
            row.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(row) {};
        }
        if (viewType == TYPE_ERROR) {
            View error = SessionListItemViews.errorItem(activity, errorMessage, onRetry);
            error.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerView.ViewHolder(error) {};
        }
        TextView empty = SessionListItemViews.emptyItem(activity);
        empty.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return new RecyclerView.ViewHolder(empty) {};
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        RowItem item = items.get(position);
        if (item.type == TYPE_GROUP_HEADER) {
            bindGroupHeader(holder.itemView, item);
        } else if (item.type == TYPE_SESSION && item.session != null && server != null) {
            SessionRowHelper.updateSessionRow(actions, holder.itemView, item.session, server);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public boolean isSessionRow(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position).type == TYPE_SESSION;
        }
        return false;
    }

    public String getSessionId(int position) {
        if (position >= 0 && position < items.size()) {
            RowItem item = items.get(position);
            if (item.type == TYPE_SESSION && item.session != null) {
                return item.session.optString("id");
            }
        }
        return null;
    }

    private void updateItems(List<RowItem> next) {
        List<RowItem> previous = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCallback(previous, next));
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    private List<RowItem> buildGroupedItems(ServerConfig server, JSONArray sessions) {
        List<SessionGroup> groups = new ArrayList<>();
        String serverKey = server == null ? "" : TerminalCacheScope.key(server);
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String cwd = normalizedCwd(session.optString("cwd", ""));
            String groupKey = serverKey + "#" + cwd;
            SessionGroup group = findGroup(groups, groupKey);
            if (group == null) {
                group = new SessionGroup(groupKey, cwd);
                groups.add(group);
            }
            group.sessions.add(session);
        }
        for (SessionGroup group : groups) {
            Collections.sort(group.sessions, SessionRecyclerAdapter::compareSessionOrder);
            group.sortKey = group.sessions.isEmpty() ? "" : sessionSortKey(group.sessions.get(0));
        }
        Collections.sort(groups, (a, b) -> {
            int byTime = a.sortKey.compareTo(b.sortKey);
            return byTime != 0 ? byTime : a.cwd.compareTo(b.cwd);
        });

        List<RowItem> next = new ArrayList<>();
        for (SessionGroup group : groups) {
            boolean collapsed = isGroupCollapsed(group.groupKey);
            DirectoryTitle title = DirectoryTitle.from(group.cwd);
            next.add(RowItem.groupHeader(group.groupKey, title.title, title.subtitle, group.sessions.size(), collapsed));
            if (!collapsed) {
                for (JSONObject session : group.sessions) {
                    next.add(RowItem.session(group.groupKey, session));
                }
            }
        }
        return next;
    }

    private static SessionGroup findGroup(List<SessionGroup> groups, String groupKey) {
        for (SessionGroup group : groups) {
            if (group.groupKey.equals(groupKey)) return group;
        }
        return null;
    }

    private View createGroupHeaderView(ViewGroup parent) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_1),
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_2 + 2),
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_1),
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_2)
        );

        LinearLayout textArea = new LinearLayout(parent.getContext());
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(parent.getContext());
        title.setTag("group_title");
        title.setTextColor(DesignTokens.TEXT_PRIMARY);
        title.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        title.setTypeface(DesignTokens.fontGeistSansSemibold(parent.getContext()));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);

        TextView subtitle = new TextView(parent.getContext());
        subtitle.setTag("group_subtitle");
        subtitle.setTextColor(DesignTokens.TEXT_TERTIARY);
        subtitle.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        subtitle.setTypeface(DesignTokens.fontGeistMono(parent.getContext()));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.START);

        textArea.addView(title, new LinearLayout.LayoutParams(-1, -2));
        textArea.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        row.addView(textArea, new LinearLayout.LayoutParams(0, -2, 1));

        TextView count = new TextView(parent.getContext());
        count.setTag("group_count");
        count.setTextColor(DesignTokens.TEXT_SECONDARY);
        count.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        count.setTypeface(DesignTokens.fontGeistMono(parent.getContext()));
        count.setGravity(Gravity.CENTER);
        row.addView(count, new LinearLayout.LayoutParams(UIUtils.dp(parent.getContext(), 38), UIUtils.dp(parent.getContext(), 32)));

        ImageView arrow = new ImageView(parent.getContext());
        arrow.setTag("group_arrow");
        arrow.setColorFilter(DesignTokens.TEXT_SECONDARY);
        arrow.setPadding(
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_1),
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_1),
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_1),
            UIUtils.dp(parent.getContext(), DesignTokens.SPACE_1)
        );
        row.addView(arrow, new LinearLayout.LayoutParams(UIUtils.dp(parent.getContext(), 28), UIUtils.dp(parent.getContext(), 32)));
        return row;
    }

    private void bindGroupHeader(View row, RowItem item) {
        TextView title = row.findViewWithTag("group_title");
        TextView subtitle = row.findViewWithTag("group_subtitle");
        TextView count = row.findViewWithTag("group_count");
        ImageView arrow = row.findViewWithTag("group_arrow");
        if (title != null) title.setText(item.title);
        if (subtitle != null) {
            subtitle.setText(item.subtitle);
            subtitle.setVisibility(item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (count != null) count.setText(String.valueOf(item.count));
        if (arrow != null) {
            arrow.setImageResource(item.collapsed
                ? com.webterm.mobile.R.drawable.ic_chevron_right
                : com.webterm.mobile.R.drawable.ic_chevron_down);
        }
        row.setOnClickListener((v) -> toggleGroup(item.groupKey));
    }

    private void toggleGroup(String groupKey) {
        if (groupKey == null || groupKey.isEmpty()) return;
        setGroupCollapsed(groupKey, !isGroupCollapsed(groupKey));
        submitSessions(server, lastSessions);
    }

    private boolean isGroupCollapsed(String groupKey) {
        return collapseState != null && collapseState.isCollapsed(groupKey);
    }

    private void setGroupCollapsed(String groupKey, boolean collapsed) {
        if (collapseState != null) collapseState.setCollapsed(groupKey, collapsed);
    }

    private boolean hasSessionRowsInGroup(String groupKey) {
        for (RowItem item : items) {
            if (item.type == TYPE_SESSION && groupKey.equals(item.groupKey)) return true;
        }
        return false;
    }

    private int findGroupHeaderIndex(String groupKey) {
        for (int i = 0; i < items.size(); i++) {
            RowItem item = items.get(i);
            if (item.type == TYPE_GROUP_HEADER && groupKey.equals(item.groupKey)) return i;
        }
        return -1;
    }

    private boolean removeFromLastSessions(String sessionId) {
        if (lastSessions == null) return false;
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < lastSessions.length(); i++) {
            JSONObject session = lastSessions.optJSONObject(i);
            if (session != null && !sessionId.equals(session.optString("id"))) {
                next.put(session);
            } else if (session != null) {
                removed = true;
            }
        }
        if (!removed) return false;
        lastSessions = next;
        return true;
    }

    private static JSONArray copySessions(JSONArray sessions) {
        JSONArray copy = new JSONArray();
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null) copy.put(session);
        }
        return copy;
    }

    private static int compareSessionOrder(JSONObject first, JSONObject second) {
        int byTime = sessionSortKey(first).compareTo(sessionSortKey(second));
        if (byTime != 0) return byTime;
        return sessionIdentity(first).compareTo(sessionIdentity(second));
    }

    private static String sessionSortKey(JSONObject session) {
        String createdAt = session.optString("createdAt", "").trim();
        if (!createdAt.isEmpty()) return createdAt;
        return "~" + sessionIdentity(session);
    }

    private static String sessionIdentity(JSONObject session) {
        String id = session.optString("id");
        String identity = SessionIdentity.value(
            id,
            session.optString("instanceId", ""),
            session.optString("createdAt", "")
        );
        return identity.isEmpty() ? "id:" + id : identity;
    }

    private static String normalizedCwd(String cwd) {
        String value = String.valueOf(cwd == null ? "" : cwd).trim();
        if (value.isEmpty()) return "";
        while (value.length() > 1 && (value.endsWith("/") || value.endsWith("\\"))) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static final class DiffCallback extends DiffUtil.Callback {
        private final List<RowItem> oldItems;
        private final List<RowItem> newItems;

        DiffCallback(List<RowItem> oldItems, List<RowItem> newItems) {
            this.oldItems = oldItems;
            this.newItems = newItems;
        }

        @Override
        public int getOldListSize() {
            return oldItems.size();
        }

        @Override
        public int getNewListSize() {
            return newItems.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldItems.get(oldItemPosition).key.equals(newItems.get(newItemPosition).key);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return oldItems.get(oldItemPosition).content.equals(newItems.get(newItemPosition).content);
        }
    }

    private static final class RowItem {
        final int type;
        final String key;
        final String id;
        final String groupKey;
        final JSONObject session;
        final String content;
        final String title;
        final String subtitle;
        final int count;
        final boolean collapsed;

        private RowItem(int type, String key, String id, String groupKey, JSONObject session, String content, String title, String subtitle, int count, boolean collapsed) {
            this.type = type;
            this.key = key;
            this.id = id;
            this.groupKey = groupKey;
            this.session = session;
            this.content = content;
            this.title = title;
            this.subtitle = subtitle;
            this.count = count;
            this.collapsed = collapsed;
        }

        static RowItem groupHeader(String groupKey, String title, String subtitle, int count, boolean collapsed) {
            String content = title + '\u001f' + subtitle + '\u001f' + count + '\u001f' + collapsed;
            return new RowItem(TYPE_GROUP_HEADER, "group:" + groupKey, "", groupKey, null, content, title, subtitle, count, collapsed);
        }

        static RowItem session(String groupKey, JSONObject session) {
            String id = session.optString("id");
            String identity = sessionIdentity(session);
            return new RowItem(TYPE_SESSION, "session:" + identity, id, groupKey, session, uiContent(session), "", "", 0, false);
        }

        static RowItem empty() {
            return new RowItem(TYPE_EMPTY, "empty", "", "", null, "empty", "", "", 0, false);
        }

        static RowItem error(String message) {
            return new RowItem(TYPE_ERROR, "error", "", "", null, "error:" + message, "", "", 0, false);
        }

        long stableId() {
            return key.hashCode();
        }

        private static String uiContent(JSONObject session) {
            StringBuilder builder = new StringBuilder();
            append(builder, session.optString("id"));
            append(builder, session.optString("instanceId"));
            append(builder, session.optString("createdAt"));
            append(builder, session.optString("name"));
            append(builder, session.optString("termTitle"));
            append(builder, session.optString("recentInputLines"));
            append(builder, session.optString("recentInputHidden"));
            return builder.toString();
        }

        private static void append(StringBuilder builder, String value) {
            builder.append(value == null ? "" : value).append('\u001f');
        }
    }

    private static final class SessionGroup {
        final String groupKey;
        final String cwd;
        final List<JSONObject> sessions = new ArrayList<>();
        String sortKey = "";

        SessionGroup(String groupKey, String cwd) {
            this.groupKey = groupKey;
            this.cwd = cwd;
        }
    }

    private static final class DirectoryTitle {
        final String title;
        final String subtitle;

        private DirectoryTitle(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        static DirectoryTitle from(String cwd) {
            if (cwd == null || cwd.isEmpty()) return new DirectoryTitle("未同步目录", "-");
            if ("/".equals(cwd) || "\\".equals(cwd)) return new DirectoryTitle(cwd, "");
            int slash = Math.max(cwd.lastIndexOf('/'), cwd.lastIndexOf('\\'));
            if (slash < 0) return new DirectoryTitle(cwd, "");
            String title = cwd.substring(slash + 1);
            String parent = slash == 0 ? cwd.substring(0, 1) : cwd.substring(0, slash);
            if (title.isEmpty()) title = cwd;
            return new DirectoryTitle(title, parent);
        }
    }

    public interface CollapseState {
        boolean isCollapsed(String groupKey);
        void setCollapsed(String groupKey, boolean collapsed);
    }
}
