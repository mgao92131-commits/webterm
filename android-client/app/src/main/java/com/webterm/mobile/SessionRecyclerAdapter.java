package com.webterm.mobile;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class SessionRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SESSION = 1;
    private static final int TYPE_EMPTY = 2;
    private static final int TYPE_ERROR = 3;

    private final Activity activity;
    private final SessionRowActions actions;
    private final Runnable onRetry;
    private final List<RowItem> items = new ArrayList<>();
    private ServerConfig server;
    private String errorMessage;

    SessionRecyclerAdapter(Activity activity, SessionRowActions actions, Runnable onRetry) {
        this.activity = activity;
        this.actions = actions;
        this.onRetry = onRetry;
        setHasStableIds(true);
        showEmpty();
    }

    void submitSessions(ServerConfig server, JSONArray sessions) {
        this.server = server;
        this.errorMessage = null;
        List<RowItem> next = new ArrayList<>();
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            next.add(RowItem.session(session));
        }
        if (next.isEmpty()) next.add(RowItem.empty());
        updateItems(next);
    }

    void showError(String message) {
        errorMessage = message == null || message.isEmpty() ? "连接失败" : message;
        List<RowItem> next = new ArrayList<>();
        next.add(RowItem.error(errorMessage));
        updateItems(next);
    }

    void showEmpty() {
        errorMessage = null;
        List<RowItem> next = new ArrayList<>();
        next.add(RowItem.empty());
        updateItems(next);
    }

    boolean hasSessionRows() {
        for (RowItem item : items) {
            if (item.type == TYPE_SESSION) return true;
        }
        return false;
    }

    void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            RowItem item = items.get(i);
            if (item.type == TYPE_SESSION && sessionId.equals(item.id)) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        items.remove(index);
        notifyItemRemoved(index);
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
        if (item.type == TYPE_SESSION && item.session != null && server != null) {
            SessionRowHelper.updateSessionRow(actions, holder.itemView, item.session, server);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private void updateItems(List<RowItem> next) {
        List<RowItem> previous = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCallback(previous, next));
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
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
        final JSONObject session;
        final String content;

        private RowItem(int type, String key, String id, JSONObject session, String content) {
            this.type = type;
            this.key = key;
            this.id = id;
            this.session = session;
            this.content = content;
        }

        static RowItem session(JSONObject session) {
            String id = session.optString("id");
            return new RowItem(TYPE_SESSION, "session:" + id, id, session, session.toString());
        }

        static RowItem empty() {
            return new RowItem(TYPE_EMPTY, "empty", "", null, "empty");
        }

        static RowItem error(String message) {
            return new RowItem(TYPE_ERROR, "error", "", null, "error:" + message);
        }

        long stableId() {
            return key.hashCode();
        }
    }
}
