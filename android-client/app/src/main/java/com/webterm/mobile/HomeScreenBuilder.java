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

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

final class HomeScreenBuilder {
    private HomeScreenBuilder() {}

    static HomeResult buildHome(Activity activity, Runnable onAddServer, Runnable onSettings, Runnable onRefresh, Runnable onRelaySettings, Runnable onCrashLogs) {
        return buildTopLevel(activity, "WebTerm", "设备列表", onAddServer, onSettings, onRefresh, onRelaySettings, onCrashLogs);
    }

    static DeviceSessionsResult buildDeviceSessions(
        Activity activity,
        ServerConfig server,
        Runnable onBack,
        Runnable onCreateSession,
        Runnable onRefresh,
        Runnable onEditServer,
        Runnable onRemoveServer
    ) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15, 15, 18));

        LinearLayout topbar = new LinearLayout(activity);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton backBtn = new ImageButton(activity);
        backBtn.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        backBtn.setColorFilter(Color.rgb(243, 244, 246));
        backBtn.setBackground(outlineBackground(activity, UIUtils.dp(activity, 20)));
        backBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));
        backBtn.setOnClickListener((v) -> onBack.run());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(UIUtils.dp(activity, 12), 0, 0, 0);
        TextView title = new TextView(activity);
        title.setText(server.getName());
        title.setTextColor(Color.rgb(243, 244, 246));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView subtitle = new TextView(activity);
        subtitle.setText(server.isRelayDevice() ? "中转设备" : server.getUrl());
        subtitle.setTextColor(Color.rgb(156, 163, 175));
        subtitle.setTextSize(12);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        StatusIndicatorView status = new StatusIndicatorView(activity);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 10), UIUtils.dp(activity, 10));
        statusLp.setMargins(0, 0, UIUtils.dp(activity, 10), 0);
        topbar.addView(status, statusLp);

        ImageButton addBtn = new ImageButton(activity);
        addBtn.setImageResource(com.webterm.mobile.R.drawable.ic_add);
        addBtn.setColorFilter(Color.rgb(16, 185, 129));
        addBtn.setBackground(outlineBackground(activity, UIUtils.dp(activity, 20)));
        addBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));
        addBtn.setOnClickListener((v) -> onCreateSession.run());
        topbar.addView(addBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        ImageButton moreBtn = new ImageButton(activity);
        moreBtn.setImageResource(com.webterm.mobile.R.drawable.ic_more_vert);
        moreBtn.setColorFilter(Color.rgb(243, 244, 246));
        moreBtn.setBackground(outlineBackground(activity, UIUtils.dp(activity, 20)));
        moreBtn.setPadding(0, 0, 0, 0);
        moreBtn.setOnClickListener((v) -> {
            PopupMenu popup = new PopupMenu(activity, moreBtn);
            popup.getMenu().add(0, 1, 0, "刷新终端");
            if (!server.isRelayDevice()) {
                popup.getMenu().add(0, 2, 1, "修改配置");
                popup.getMenu().add(0, 3, 2, "移除电脑");
            }
            popup.setOnMenuItemClickListener((item) -> {
                if (item.getItemId() == 1) {
                    onRefresh.run();
                    return true;
                } else if (item.getItemId() == 2) {
                    onEditServer.run();
                    return true;
                } else if (item.getItemId() == 3) {
                    onRemoveServer.run();
                    return true;
                }
                return false;
            });
            popup.show();
        });
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40));
        moreLp.setMargins(UIUtils.dp(activity, 8), 0, 0, 0);
        topbar.addView(moreBtn, moreLp);

        root.addView(topbar, new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 58)));

        RecyclerView sessionList = new RecyclerView(activity);
        sessionList.setLayoutManager(new LinearLayoutManager(activity));
        sessionList.setItemAnimator(new DefaultItemAnimator());
        sessionList.setClipToPadding(false);
        root.addView(sessionList, new LinearLayout.LayoutParams(-1, 0, 1));

        DeviceSessionsResult result = new DeviceSessionsResult();
        result.root = root;
        result.sessionList = sessionList;
        result.status = status;
        return result;
    }

    private static HomeResult buildTopLevel(Activity activity, String titleText, String subtitleText, Runnable onAddServer, Runnable onSettings, Runnable onRefresh, Runnable onRelaySettings, Runnable onCrashLogs) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15, 15, 18));

        LinearLayout topbar = new LinearLayout(activity);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(Color.rgb(243, 244, 246));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = new TextView(activity);
        subtitle.setText(subtitleText);
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
            popup.getMenu().add(0, 1, 0, "添加电脑");
            popup.getMenu().add(0, 2, 0, "终端设置");
            popup.getMenu().add(0, 3, 0, "中转服务");
            popup.getMenu().add(0, 4, 0, "刷新设备");
            popup.getMenu().add(0, 5, 0, "导出崩溃日志");
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
                } else if (item.getItemId() == 5) {
                    onCrashLogs.run();
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
        empty.setText("暂无保存的电脑\n点击右上角按钮添加电脑");
        empty.setTextColor(Color.rgb(147, 161, 161));
        empty.setTextSize(15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(UIUtils.dp(activity, 20), UIUtils.dp(activity, 80), UIUtils.dp(activity, 20), UIUtils.dp(activity, 80));
        return empty;
    }

    static View deviceCard(Activity activity, ServerConfig server, View.OnClickListener onClick, Runnable onEditServer, Runnable onRemoveServer) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(UIUtils.dp(activity, 14), UIUtils.dp(activity, 12), UIUtils.dp(activity, 10), UIUtils.dp(activity, 12));
        card.setBackground(UIUtils.panelBackground(activity));
        card.setOnClickListener(onClick);

        TextView badge = new TextView(activity);
        badge.setText(server.isRelayDevice() ? "R" : "PC");
        badge.setTextColor(server.isRelayDevice() ? Color.rgb(96, 165, 250) : Color.rgb(16, 185, 129));
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setColor(server.isRelayDevice() ? Color.argb(30, 96, 165, 250) : Color.argb(30, 16, 185, 129));
        badgeBg.setCornerRadius(UIUtils.dp(activity, 6));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 42), UIUtils.dp(activity, 42));
        badgeLp.setMargins(0, 0, UIUtils.dp(activity, 12), 0);
        card.addView(badge, badgeLp);

        LinearLayout textArea = new LinearLayout(activity);
        textArea.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(server.getName());
        name.setTextColor(Color.rgb(243, 244, 246));
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView detail = new TextView(activity);
        detail.setText(server.isRelayDevice() ? "中转设备" : server.getUrl());
        detail.setTextColor(Color.rgb(156, 163, 175));
        detail.setTextSize(12);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textArea.addView(name, new LinearLayout.LayoutParams(-1, -2));
        textArea.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        card.addView(textArea, new LinearLayout.LayoutParams(0, -2, 1));

        if (!server.isRelayDevice()) {
            TextView menuBtn = new TextView(activity);
            menuBtn.setText("⋮");
            menuBtn.setTextColor(Color.rgb(156, 163, 175));
            menuBtn.setTextSize(18);
            menuBtn.setGravity(Gravity.CENTER);
            menuBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 4), UIUtils.dp(activity, 8), UIUtils.dp(activity, 4));
            menuBtn.setOnClickListener((v) -> {
                PopupMenu popup = new PopupMenu(activity, menuBtn);
                popup.getMenu().add(0, 1, 0, "修改配置");
                popup.getMenu().add(0, 2, 1, "移除电脑");
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
            card.addView(menuBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 36), UIUtils.dp(activity, 36)));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UIUtils.dp(activity, 12));
        card.setLayoutParams(lp);
        return card;
    }

    private static GradientDrawable outlineBackground(Activity activity, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(radius);
        bg.setStroke(UIUtils.dp(activity, 1), Color.rgb(55, 65, 81));
        return bg;
    }

    static final class HomeResult {
        LinearLayout root;
        LinearLayout sessionList;
        TextView subtitle;
    }

    static final class DeviceSessionsResult {
        LinearLayout root;
        RecyclerView sessionList;
        StatusIndicatorView status;
    }
}
