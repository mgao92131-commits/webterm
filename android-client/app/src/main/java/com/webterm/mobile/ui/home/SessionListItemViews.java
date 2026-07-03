package com.webterm.mobile.ui.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.UIUtils;

public final class SessionListItemViews {
    private SessionListItemViews() {}

    static TextView emptyItem(Activity activity) {
        TextView empty = new TextView(activity);
        empty.setTag("empty_item");
        empty.setText("还没有终端会话");
        empty.setTextColor(DesignTokens.TEXT_SECONDARY);
        empty.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        empty.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3)
        );
        return empty;
    }

    static View errorItem(Activity activity, String error, Runnable onRetry) {
        LinearLayout container = new LinearLayout(activity);
        container.setTag("error_item");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, UIUtils.dp(activity, DesignTokens.SPACE_4));

        TextView errText = new TextView(activity);
        errText.setText("无法连接到服务器: " + error);
        errText.setTextColor(DesignTokens.TEXT_SECONDARY);
        errText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        errText.setGravity(Gravity.CENTER);
        errText.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2 + 2));
        container.addView(errText);

        // 重新连接按钮：ImageView（刷新图标）+ TextView（"重新连接"）
        LinearLayout retryBtn = new LinearLayout(activity);
        retryBtn.setOrientation(LinearLayout.HORIZONTAL);
        retryBtn.setGravity(Gravity.CENTER);
        retryBtn.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_2)
        );

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setColor(DesignTokens.BG_TERTIARY);
        btnBg.setStroke(UIUtils.dp(activity, 1), DesignTokens.BORDER_PRIMARY);
        btnBg.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));
        retryBtn.setBackground(btnBg);
        retryBtn.setOnClickListener((v) -> onRetry.run());

        ImageView refreshIcon = new ImageView(activity);
        refreshIcon.setImageResource(com.webterm.mobile.R.drawable.ic_refresh);
        refreshIcon.setColorFilter(DesignTokens.TEXT_PRIMARY);
        int iconSize = (int) DesignTokens.TEXT_BODY_SIZE + 2;
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                UIUtils.dp(activity, iconSize),
                UIUtils.dp(activity, iconSize));
        iconLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        retryBtn.addView(refreshIcon, iconLp);

        TextView retryLabel = new TextView(activity);
        retryLabel.setText("重新连接");
        retryLabel.setTextColor(DesignTokens.TEXT_PRIMARY);
        retryLabel.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        retryBtn.addView(retryLabel);

        container.addView(retryBtn, new LinearLayout.LayoutParams(-2, -2));
        return container;
    }
}
