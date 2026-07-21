package com.webterm.feature.home;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.webterm.core.config.ServerConfig;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.StatusIndicatorView;
import com.webterm.ui.common.UIUtils;

public final class HomeScreenBuilder {
    private HomeScreenBuilder() {}

    // 三点菜单项 ID（避免裸数字）。
    private static final int MENU_ADD_DIRECT = 1;
    private static final int MENU_RELAY = 2;
    private static final int MENU_REFRESH = 3;
    private static final int MENU_SETTINGS = 4;
    private static final int MENU_CRASH_LOGS = 5;
    private static final int MENU_DIAGNOSTIC_LOGS = 6;

	public static HomeResult buildHome(Activity activity, Runnable onAddDirectDevice, Runnable onSettings,
                                     Runnable onRefresh, Runnable onRelaySettings,
                                     Runnable onCrashLogs, boolean canShareDiagnosticLogs,
                                     Runnable onDiagnosticLogs) {
		return buildTopLevel(activity, "WebTerm", "设备列表", onAddDirectDevice, onSettings, onRefresh,
                onRelaySettings, onCrashLogs, canShareDiagnosticLogs, onDiagnosticLogs);
	}

	private static HomeResult buildTopLevel(Activity activity, String titleText, String subtitleText,
                                          Runnable onAddDirectDevice, Runnable onSettings,
                                          Runnable onRefresh, Runnable onRelaySettings, Runnable onCrashLogs,
                                          boolean canShareDiagnosticLogs, Runnable onDiagnosticLogs) {
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
        moreBtn.setImageResource(com.webterm.feature.home.R.drawable.ic_more_vert);
        moreBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        moreBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        moreBtn.setPadding(0, 0, 0, 0);
        moreBtn.setOnClickListener((v) -> {
            PopupMenu popup = new PopupMenu(activity, moreBtn);
			popup.getMenu().add(0, MENU_ADD_DIRECT, 0, "添加直连设备");
			popup.getMenu().add(0, MENU_RELAY, 1, "中转服务");
			popup.getMenu().add(0, MENU_REFRESH, 2, "刷新设备");
			popup.getMenu().add(0, MENU_SETTINGS, 3, "终端设置");
			popup.getMenu().add(0, MENU_CRASH_LOGS, 4, "导出崩溃日志");
			if (canShareDiagnosticLogs) {
				popup.getMenu().add(0, MENU_DIAGNOSTIC_LOGS, 5, "导出诊断日志");
			}
			popup.setOnMenuItemClickListener((item) -> {
				int id = item.getItemId();
				if (id == MENU_ADD_DIRECT) {
					onAddDirectDevice.run();
					return true;
				} else if (id == MENU_RELAY) {
					onRelaySettings.run();
					return true;
				} else if (id == MENU_REFRESH) {
					onRefresh.run();
					return true;
				} else if (id == MENU_SETTINGS) {
					onSettings.run();
					return true;
				} else if (id == MENU_CRASH_LOGS) {
                    onCrashLogs.run();
                    return true;
                } else if (id == MENU_DIAGNOSTIC_LOGS) {
                    onDiagnosticLogs.run();
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
		empty.setText("暂无设备\n可从右上角菜单添加直连设备，或配置中转服务");
        empty.setTextColor(DesignTokens.TEXT_TERTIARY);
        empty.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_5), UIUtils.dp(activity, 80), UIUtils.dp(activity, DesignTokens.SPACE_5), UIUtils.dp(activity, 80));
        return empty;
    }

	public static View deviceCard(Activity activity, ServerConfig server, View.OnClickListener onClick,
                                 DirectCardActions directActions) {
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

        boolean direct = server.isDirectDevice();
        int accent = direct ? DesignTokens.SUCCESS : DesignTokens.INFO;

        ImageView badge = new ImageView(activity);
        badge.setImageResource(direct
            ? com.webterm.feature.home.R.drawable.ic_device_direct
            : com.webterm.feature.home.R.drawable.ic_device_relay);
        badge.setColorFilter(accent);
        int badgePadding = UIUtils.dp(activity, DesignTokens.SPACE_2);
        badge.setPadding(badgePadding, badgePadding, badgePadding, badgePadding);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setColor(DesignTokens.withAlpha(accent, 0x4D));
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
		detail.setText(direct ? "直连设备 · Direct" : "中转设备 · Relay");
        detail.setTextColor(DesignTokens.TEXT_SECONDARY);
        detail.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView address = new TextView(activity);
        address.setText(displayAddress(server));
        address.setTextColor(DesignTokens.TEXT_TERTIARY);
        address.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textArea.addView(name, new LinearLayout.LayoutParams(-1, -2));
        textArea.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        textArea.addView(address, new LinearLayout.LayoutParams(-1, -2));
        card.addView(textArea, new LinearLayout.LayoutParams(0, -2, 1));

        // Direct 卡片右侧三点菜单：编辑 / 重新连接 / 删除。
        if (direct && directActions != null) {
            ImageButton moreBtn = new ImageButton(activity);
            moreBtn.setImageResource(com.webterm.feature.home.R.drawable.ic_more_vert);
            moreBtn.setColorFilter(DesignTokens.TEXT_SECONDARY);
            moreBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
            moreBtn.setPadding(0, 0, 0, 0);
            moreBtn.setOnClickListener((v) -> {
                PopupMenu popup = new PopupMenu(activity, moreBtn);
                popup.getMenu().add(0, 1, 0, "编辑");
                popup.getMenu().add(0, 2, 1, "重新连接");
                popup.getMenu().add(0, 3, 2, "删除");
                popup.setOnMenuItemClickListener((item) -> {
                    int id = item.getItemId();
                    if (id == 1) {
                        directActions.onEditDirect(server);
                        return true;
                    } else if (id == 2) {
                        directActions.onReconnectDirect(server);
                        return true;
                    } else if (id == 3) {
                        directActions.onDeleteDirect(server);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                UIUtils.dp(activity, 36), UIUtils.dp(activity, 36));
            moreLp.setMargins(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);
            card.addView(moreBtn, moreLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3));
        card.setLayoutParams(lp);
        return card;
    }

    /** 卡片附加信息：去掉协议前缀后的 host[:port]，Direct 显示地址、Relay 显示中转服务器。 */
    private static String displayAddress(ServerConfig server) {
        String url = server.getUrl();
        if (url == null || url.isEmpty()) return "";
        return url.replaceFirst("^https?://", "");
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
}
