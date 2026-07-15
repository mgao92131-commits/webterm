package com.webterm.feature.home;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.webterm.core.config.ServerConfig;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.StatusIndicatorView;
import com.webterm.ui.common.UIUtils;

public final class DeviceSessionsScreenBuilder {
    private DeviceSessionsScreenBuilder() {}

    public static Result build(
        Activity activity,
		ServerConfig server,
		Runnable onBack,
		Runnable onCreateSession,
		Runnable onRefresh
    ) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        ImageButton backBtn = new ImageButton(activity);
        backBtn.setImageResource(com.webterm.ui.common.R.drawable.ic_arrow_back);
        backBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        backBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        backBtn.setPadding(0, 0, 0, 0);
        backBtn.setOnClickListener((v) -> onBack.run());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

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

        LinearLayout subtitleContainer = new LinearLayout(activity);
        subtitleContainer.setOrientation(LinearLayout.HORIZONTAL);
        subtitleContainer.setGravity(Gravity.CENTER_VERTICAL);

        StatusIndicatorView status = new StatusIndicatorView(activity);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
            UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE),
            UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE));
        statusLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        subtitleContainer.addView(status, statusLp);

        TextView subtitle = new TextView(activity);
		subtitle.setText("中转设备");
        subtitle.setTextColor(DesignTokens.TEXT_SECONDARY);
        subtitle.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        subtitleContainer.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        titles.addView(subtitleContainer, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton addBtn = new ImageButton(activity);
        addBtn.setImageResource(com.webterm.ui.common.R.drawable.ic_add);
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

        Result result = new Result();
        result.root = root;
        result.sessionList = sessionList;
        result.status = status;
        return result;
    }

    public static final class Result {
        public LinearLayout root;
        public RecyclerView sessionList;
        public StatusIndicatorView status;
    }
}
