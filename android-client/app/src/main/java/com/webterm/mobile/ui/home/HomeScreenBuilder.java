package com.webterm.mobile.ui.home;

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

import com.webterm.core.config.ServerConfig;
import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.UIUtils;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class HomeScreenBuilder {
    private HomeScreenBuilder() {}

    public static HomeResult buildHome(Activity activity, Runnable onAddServer, Runnable onSettings, Runnable onRefresh, Runnable onRelaySettings, Runnable onCrashLogs) {
        return buildTopLevel(activity, "WebTerm", "设备列表", onAddServer, onSettings, onRefresh, onRelaySettings, onCrashLogs);
    }

    public static DeviceSessionsResult buildDeviceSessions(
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
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // 统一顶栏（bg_secondary 背景 + 分割线）
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        ImageButton backBtn = new ImageButton(activity);
        backBtn.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        backBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        backBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        backBtn.setPadding(0, 0, 0, 0);
        backBtn.setOnClickListener((v) -> onBack.run());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        // heading: [标题/副标题]
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(server.getName());
        title.setTextColor(DesignTokens.TEXT_PRIMARY);
        title.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        title.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 副标题容器：[指示灯] + [副标题]
        LinearLayout subtitleContainer = new LinearLayout(activity);
        subtitleContainer.setOrientation(LinearLayout.HORIZONTAL);
        subtitleContainer.setGravity(Gravity.CENTER_VERTICAL);

        StatusIndicatorView status = new StatusIndicatorView(activity);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE), UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE));
        statusLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        subtitleContainer.addView(status, statusLp);

        TextView subtitle = new TextView(activity);
        subtitle.setText(server.isRelayDevice() ? "中转设备" : server.getUrl());
        subtitle.setTextColor(DesignTokens.TEXT_SECONDARY);
        subtitle.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        subtitleContainer.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        titles.addView(subtitleContainer, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton addBtn = new ImageButton(activity);
        addBtn.setImageResource(com.webterm.mobile.R.drawable.ic_add);
        addBtn.setColorFilter(DesignTokens.ACCENT);
        addBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        addBtn.setPadding(0, 0, 0, 0);
        addBtn.setOnClickListener((v) -> onCreateSession.run());
        topbar.addView(addBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));



        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        RecyclerView sessionList = new RecyclerView(activity);
        sessionList.setLayoutManager(new LinearLayoutManager(activity));
        sessionList.setItemAnimator(new DefaultItemAnimator());
        sessionList.setClipToPadding(false);
        sessionList.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
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
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // 统一顶栏
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        // heading: [标题/副标题]
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(DesignTokens.TEXT_PRIMARY);
        title.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        title.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        titles.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 副标题容器：[指示灯] + [副标题]
        LinearLayout subtitleContainer = new LinearLayout(activity);
        subtitleContainer.setOrientation(LinearLayout.HORIZONTAL);
        subtitleContainer.setGravity(Gravity.CENTER_VERTICAL);

        StatusIndicatorView homeStatus = new StatusIndicatorView(activity);
        LinearLayout.LayoutParams homeStatusLp = new LinearLayout.LayoutParams(
            UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE),
            UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE));
        homeStatusLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        subtitleContainer.addView(homeStatus, homeStatusLp);

        TextView subtitle = new TextView(activity);
        subtitle.setText(subtitleText);
        subtitle.setTextColor(DesignTokens.TEXT_SECONDARY);
        subtitle.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        subtitleContainer.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        titles.addView(subtitleContainer, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton moreBtn = new ImageButton(activity);
        moreBtn.setImageResource(com.webterm.mobile.R.drawable.ic_more_vert);
        moreBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        moreBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
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
        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            0);
        LinearLayout sessionList = new LinearLayout(activity);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(sessionList, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        HomeResult result = new HomeResult();
        result.root = root;
        result.sessionList = sessionList;
        result.subtitle = subtitle;
        result.homeStatus = homeStatus;
        return result;
    }

    public static TextView emptyState(Activity activity) {
        TextView empty = new TextView(activity);
        empty.setText("暂无保存的电脑\n点击右上角按钮添加电脑");
        empty.setTextColor(DesignTokens.TEXT_TERTIARY);
        empty.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_5), UIUtils.dp(activity, 80), UIUtils.dp(activity, DesignTokens.SPACE_5), UIUtils.dp(activity, 80));
        return empty;
    }

    public static View deviceCard(Activity activity, ServerConfig server, View.OnClickListener onClick, Runnable onEditServer, Runnable onRemoveServer) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(
            UIUtils.dp(activity, DesignTokens.CARD_PADDING_HORIZONTAL),
            UIUtils.dp(activity, DesignTokens.CARD_PADDING_VERTICAL),
            UIUtils.dp(activity, DesignTokens.SPACE_2 + 2),
            UIUtils.dp(activity, DesignTokens.CARD_PADDING_VERTICAL)
        );
        card.setBackground(UIUtils.panelBackground(activity));
        card.setOnClickListener(onClick);

        TextView badge = new TextView(activity);
        badge.setText(server.isRelayDevice() ? "R" : "PC");
        int badgeTextColor = server.isRelayDevice() ? DesignTokens.INFO : DesignTokens.ACCENT;
        badge.setTextColor(badgeTextColor);
        badge.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        badge.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        int badgeBgColor = server.isRelayDevice()
            ? DesignTokens.withAlpha(DesignTokens.INFO, 0x4D)
            : DesignTokens.accentBgStrong();
        badgeBg.setColor(badgeBgColor);
        badgeBg.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.DEVICE_BADGE_SIZE), UIUtils.dp(activity, DesignTokens.DEVICE_BADGE_SIZE));
        badgeLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0);
        card.addView(badge, badgeLp);

        LinearLayout textArea = new LinearLayout(activity);
        textArea.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(server.getName());
        name.setTextColor(DesignTokens.TEXT_PRIMARY);
        name.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        name.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView detail = new TextView(activity);
        if (server.isRelayDevice()) {
            detail.setText(server.isP2PEnabled() ? "中转设备 · P2P" : "中转设备 · Relay");
        } else {
            detail.setText(server.getUrl());
        }
        detail.setTextColor(DesignTokens.TEXT_SECONDARY);
        detail.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textArea.addView(name, new LinearLayout.LayoutParams(-1, -2));
        textArea.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        card.addView(textArea, new LinearLayout.LayoutParams(0, -2, 1));

        if (!server.isRelayDevice()) {
            TextView menuBtn = new TextView(activity);
            menuBtn.setText("⋮");
            menuBtn.setTextColor(DesignTokens.TEXT_SECONDARY);
            menuBtn.setTextSize(DesignTokens.TEXT_BODY_SIZE + 2);
            menuBtn.setGravity(Gravity.CENTER);
            menuBtn.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), UIUtils.dp(activity, DesignTokens.SPACE_1), UIUtils.dp(activity, DesignTokens.SPACE_2), UIUtils.dp(activity, DesignTokens.SPACE_1));
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
        lp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3));
        card.setLayoutParams(lp);
        return card;
    }

    private static GradientDrawable outlineBackground(Activity activity, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setCornerRadius(radius);
        bg.setStroke(UIUtils.dp(activity, 1), DesignTokens.BORDER_PRIMARY);
        return bg;
    }

    public static final class HomeResult {
        public LinearLayout root;
        public LinearLayout sessionList;
        public TextView subtitle;
        public StatusIndicatorView homeStatus;
    }

    public static final class DeviceSessionsResult {
        public LinearLayout root;
        public RecyclerView sessionList;
        public StatusIndicatorView status;
    }
}
