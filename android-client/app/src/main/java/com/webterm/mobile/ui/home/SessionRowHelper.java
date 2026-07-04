package com.webterm.mobile.ui.home;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import com.webterm.core.config.ServerConfig;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;

public final class SessionRowHelper {

    public static void addSessionRow(final Context context, final SessionRowActions actions, JSONObject session, final ServerConfig server, LinearLayout subList) {
        View row = createSessionRowView(context);
        updateSessionRow(actions, row, session, server);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UIUtils.dp(context, 12));
        subList.addView(row, lp);
    }

    public static View createSessionRowView(final Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setBackground(UIUtils.panelBackground(context));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            UIUtils.dp(context, DesignTokens.CARD_PADDING_HORIZONTAL),
            UIUtils.dp(context, DesignTokens.CARD_PADDING_VERTICAL),
            UIUtils.dp(context, DesignTokens.CARD_PADDING_HORIZONTAL),
            UIUtils.dp(context, DesignTokens.CARD_PADDING_VERTICAL)
        );

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);

        LinearLayout titleArea = new LinearLayout(context);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setTag("title");
        titleView.setTextColor(DesignTokens.TEXT_PRIMARY);
        titleView.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        titleView.setTypeface(DesignTokens.fontGeistSansSemibold(context));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);

        TextView subtitleView = new TextView(context);
        subtitleView.setTag("subtitle");
        subtitleView.setTextColor(DesignTokens.TEXT_SECONDARY);
        subtitleView.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);

        titleArea.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        titleArea.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titleArea, new LinearLayout.LayoutParams(-1, -2));

        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // 静态添加最近输入框，通过 updateRecentInput 控制可见性
        TextView recentView = recentInputBox(context, "");
        recentView.setTag("recent_box");
        content.addView(recentView, new LinearLayout.LayoutParams(-1, -2));

        row.addView(content, new FrameLayout.LayoutParams(-1, -2));

        return row;
    }

    public static void updateSessionRow(final SessionRowActions actions, View row, JSONObject session, final ServerConfig server) {
        TextView titleView = row.findViewWithTag("title");
        TextView subtitleView = row.findViewWithTag("subtitle");
        TextView recentView = row.findViewWithTag("recent_box");

        String id = session.optString("id");
        row.setTag(id); // 绑定 Tag 以便差分查找

        String rawTermTitle = session.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = session.optString("name", "").trim();
        final String createdAt = session.optString("createdAt", "").trim();
        final String instanceId = session.optString("instanceId", "").trim();
        final String cwd = session.optString("cwd", "").trim();

        if (titleView != null) {
            if (!nameText.isEmpty()) {
                titleView.setText(nameText);
            } else {
                titleView.setText(termTitle);
            }
        }
        if (subtitleView != null) {
            if (!nameText.isEmpty()) {
                subtitleView.setText(termTitle);
                subtitleView.setVisibility(View.VISIBLE);
            } else {
                subtitleView.setVisibility(View.GONE);
            }
        }
        if (recentView != null) {
            updateRecentInput(recentView, session);
        }

        row.setOnClickListener((v) -> actions.openSession(server, id, termTitle, nameText, createdAt, instanceId, cwd));

        row.setOnLongClickListener((v) -> {
            actions.renameSession(server, id, nameText);
            return true;
        });
    }

    private static void updateRecentInput(TextView recentView, JSONObject session) {
        if (session.optBoolean("recentInputHidden", false)) {
            recentView.setText("敏感输入已隐藏");
            recentView.setVisibility(View.VISIBLE);
        } else {
            JSONArray recentLines = session.optJSONArray("recentInputLines");
            String recentText = recentInputText(recentLines);
            if (!recentText.isEmpty()) {
                recentView.setText(recentText);
                recentView.setVisibility(View.VISIBLE);
            } else {
                recentView.setVisibility(View.GONE);
            }
        }
    }

    private static TextView recentInputBox(android.content.Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        // 最近输入预览用 secondary 灰（与 Web 端 text-text-secondary 对齐），不再使用绿色（绿色暗示成功/在线）
        view.setTextColor(DesignTokens.TEXT_SECONDARY);
        view.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        view.setTypeface(DesignTokens.fontGeistMono(context));
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(
            UIUtils.dp(context, DesignTokens.SPACE_2),
            UIUtils.dp(context, DesignTokens.SPACE_1 + 2),
            UIUtils.dp(context, DesignTokens.SPACE_2),
            UIUtils.dp(context, DesignTokens.SPACE_1 + 2)
        );

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DesignTokens.BG_PRIMARY);
        gd.setCornerRadius(UIUtils.dp(context, DesignTokens.RADIUS_SM));
        view.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, UIUtils.dp(context, DesignTokens.SPACE_2), 0, 0);
        view.setLayoutParams(lp);
        return view;
    }



    private static String recentInputText(JSONArray lines) {
        if (lines == null || lines.length() == 0) return "";
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, lines.length() - 2);
        for (int i = start; i < lines.length(); i++) {
            String line = lines.optString(i, "").trim();
            if (line.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }
}
