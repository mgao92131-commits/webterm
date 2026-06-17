package com.webterm.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

final class HomeScreenBuilder {
    private HomeScreenBuilder() {}

    static HomeResult buildHome(Activity activity, Runnable onAddServer, Runnable onSettings, Runnable onRefresh, Runnable onRelaySettings) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UIUtils.dp(activity, 20), UIUtils.dp(activity, 24), UIUtils.dp(activity, 20), UIUtils.dp(activity, 16));
        root.setBackgroundColor(Color.rgb(15, 15, 18));

        LinearLayout topbar = new LinearLayout(activity);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("WebTerm");
        title.setTextColor(Color.rgb(243, 244, 246));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = new TextView(activity);
        subtitle.setText("多端会话聚合大厅");
        subtitle.setTextColor(Color.rgb(156, 163, 175));
        subtitle.setTextSize(12);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton moreBtn = new ImageButton(activity);
        moreBtn.setImageResource(com.webterm.mobile.R.drawable.ic_more_vert);
        moreBtn.setColorFilter(Color.rgb(243, 244, 246));
        moreBtn.setBackground(outlineBackground(activity, UIUtils.dp(activity, 20)));
        moreBtn.setPadding(0, 0, 0, 0);
        moreBtn.setOnClickListener((v) -> {
            PopupMenu popup = new PopupMenu(activity, moreBtn);
            popup.getMenu().add(0, 1, 0, "➕ 添加电脑");
            popup.getMenu().add(0, 2, 0, "⚙️ 终端设置");
            popup.getMenu().add(0, 3, 0, "🔗 中转服务");
            popup.getMenu().add(0, 4, 0, "🔄 刷新列表");
            popup.setOnMenuItemClickListener((item) -> {
                if (item.getItemId() == 1) {
                    onAddServer.run();
                    return true;
                } else if (item.getItemId() == 2) {
                    onSettings.run();
                    return true;
                } else if (item.getItemId() == 3) {
                    onRelaySettings.run();
                    return true;
                } else if (item.getItemId() == 4) {
                    onRefresh.run();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        topbar.addView(moreBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));
        root.addView(topbar, new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 58)));

        ScrollView scrollView = new ScrollView(activity);
        LinearLayout sessionList = new LinearLayout(activity);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(sessionList, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        HomeResult result = new HomeResult();
        result.root = root;
        result.sessionList = sessionList;
        result.subtitle = subtitle;
        return result;
    }

    static TextView emptyState(Activity activity) {
        TextView empty = new TextView(activity);
        empty.setText("📺 暂无保存的电脑\n点击右上角 ➕ 按钮添加电脑");
        empty.setTextColor(Color.rgb(147, 161, 161));
        empty.setTextSize(15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(UIUtils.dp(activity, 20), UIUtils.dp(activity, 80), UIUtils.dp(activity, 20), UIUtils.dp(activity, 80));
        return empty;
    }

    static ServerGroupResult buildServerGroup(
        Activity activity,
        ServerConfig server,
        boolean collapsed,
        CollapseCallback onCollapseChanged,
        Runnable onCreateSession,
        Runnable onEditServer,
        Runnable onRemoveServer
    ) {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, 0, 0, UIUtils.dp(activity, 12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(UIUtils.dp(activity, 6), UIUtils.dp(activity, 8), UIUtils.dp(activity, 6), UIUtils.dp(activity, 8));

        TextView arrow = new TextView(activity);
        arrow.setText(collapsed ? "▶ " : "▼ ");
        arrow.setTextColor(Color.rgb(156, 163, 175));
        arrow.setTextSize(14);
        header.addView(arrow, new LinearLayout.LayoutParams(-2, -2));

        StatusIndicatorView status = new StatusIndicatorView(activity);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));
        statusLp.setMargins(0, 0, UIUtils.dp(activity, 8), 0);
        header.addView(status, statusLp);

        TextView nameView = new TextView(activity);
        if (server.isRelayDevice) {
            nameView.setText("🔗 " + server.name);
        } else {
            nameView.setText(server.name);
        }
        nameView.setTextColor(Color.rgb(243, 244, 246));
        nameView.setTextSize(16);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));

        TextView addSessionBtn = new TextView(activity);
        addSessionBtn.setText("+");
        addSessionBtn.setTextColor(Color.rgb(16, 185, 129));
        addSessionBtn.setTextSize(15);
        addSessionBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addSessionBtn.setPadding(UIUtils.dp(activity, 10), UIUtils.dp(activity, 4), UIUtils.dp(activity, 10), UIUtils.dp(activity, 4));
        GradientDrawable addBtnBg = new GradientDrawable();
        addBtnBg.setShape(GradientDrawable.RECTANGLE);
        addBtnBg.setColor(Color.argb(25, 16, 185, 129));
        addBtnBg.setCornerRadius(UIUtils.dp(activity, 4));
        addSessionBtn.setBackground(addBtnBg);
        addSessionBtn.setOnClickListener((v) -> onCreateSession.run());
        header.addView(addSessionBtn, new LinearLayout.LayoutParams(-2, -2));

        header.addView(new View(activity), new LinearLayout.LayoutParams(UIUtils.dp(activity, 8), 1));

        if (!server.isRelayDevice) {
            TextView menuBtn = new TextView(activity);
            menuBtn.setText("⋮");
            menuBtn.setTextColor(Color.rgb(156, 163, 175));
            menuBtn.setTextSize(18);
            menuBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 4), UIUtils.dp(activity, 8), UIUtils.dp(activity, 4));
            menuBtn.setOnClickListener((v) -> {
                PopupMenu popup = new PopupMenu(activity, menuBtn);
                popup.getMenu().add(0, 1, 0, "✏️ 修改配置");
                popup.getMenu().add(0, 2, 0, "❌ 移除电脑");
                popup.setOnMenuItemClickListener((item) -> {
                    if (item.getItemId() == 1) {
                        onEditServer.run();
                        return true;
                    } else if (item.getItemId() == 2) {
                        onRemoveServer.run();
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
            header.addView(menuBtn, new LinearLayout.LayoutParams(-2, -2));
        } else {
            // 给中转设备占一个空白间距，让UI结构一致
            View spacer = new View(activity);
            header.addView(spacer, new LinearLayout.LayoutParams(UIUtils.dp(activity, 20), 1));
        }

        group.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout subList = new LinearLayout(activity);
        subList.setOrientation(LinearLayout.VERTICAL);
        subList.setPadding(UIUtils.dp(activity, 8), 0, 0, 0);
        subList.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        group.addView(subList, new LinearLayout.LayoutParams(-1, -2));

        header.setOnClickListener((v) -> {
            boolean nextCollapsed = subList.getVisibility() == View.VISIBLE;
            subList.setVisibility(nextCollapsed ? View.GONE : View.VISIBLE);
            arrow.setText(nextCollapsed ? "▶ " : "▼ ");
            onCollapseChanged.onChanged(nextCollapsed);
        });

        ServerGroupResult result = new ServerGroupResult();
        result.group = group;
        result.subList = subList;
        result.status = status;
        return result;
    }

    private static GradientDrawable outlineBackground(Activity activity, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(radius);
        bg.setStroke(UIUtils.dp(activity, 1), Color.rgb(55, 65, 81));
        return bg;
    }

    interface CollapseCallback {
        void onChanged(boolean collapsed);
    }

    static final class HomeResult {
        LinearLayout root;
        LinearLayout sessionList;
        TextView subtitle;
    }

    static final class ServerGroupResult {
        LinearLayout group;
        LinearLayout subList;
        StatusIndicatorView status;
    }
}
