package com.webterm.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class SessionListItemViews {
    private SessionListItemViews() {}

    static TextView emptyItem(Activity activity) {
        TextView empty = new TextView(activity);
        empty.setTag("empty_item");
        empty.setText("还没有终端会话");
        empty.setTextColor(Color.rgb(156, 163, 175));
        empty.setTextSize(13);
        empty.setPadding(UIUtils.dp(activity, 12), UIUtils.dp(activity, 12), UIUtils.dp(activity, 12), UIUtils.dp(activity, 12));
        return empty;
    }

    static View errorItem(Activity activity, String error, Runnable onRetry) {
        LinearLayout container = new LinearLayout(activity);
        container.setTag("error_item");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(0, UIUtils.dp(activity, 16), 0, UIUtils.dp(activity, 16));

        TextView errText = new TextView(activity);
        errText.setText("无法连接到服务器: " + error);
        errText.setTextColor(Color.rgb(156, 163, 175));
        errText.setTextSize(13);
        errText.setGravity(Gravity.CENTER);
        errText.setPadding(0, 0, 0, UIUtils.dp(activity, 10));
        container.addView(errText);

        TextView retryBtn = new TextView(activity);
        retryBtn.setText("🔄 重新连接");
        retryBtn.setTextColor(Color.rgb(243, 244, 246));
        retryBtn.setTextSize(13);
        retryBtn.setGravity(Gravity.CENTER);
        retryBtn.setPadding(UIUtils.dp(activity, 20), UIUtils.dp(activity, 8), UIUtils.dp(activity, 20), UIUtils.dp(activity, 8));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setColor(Color.rgb(45, 45, 52));
        btnBg.setStroke(UIUtils.dp(activity, 1), Color.rgb(75, 85, 99));
        btnBg.setCornerRadius(UIUtils.dp(activity, 6));
        retryBtn.setBackground(btnBg);
        retryBtn.setOnClickListener((v) -> onRetry.run());

        container.addView(retryBtn, new LinearLayout.LayoutParams(-2, -2));
        return container;
    }
}
