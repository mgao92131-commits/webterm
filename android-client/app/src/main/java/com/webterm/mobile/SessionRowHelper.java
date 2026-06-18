package com.webterm.mobile;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

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
        content.setPadding(UIUtils.dp(context, 14), UIUtils.dp(context, 12), UIUtils.dp(context, 14), UIUtils.dp(context, 12));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);

        LinearLayout titleArea = new LinearLayout(context);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setTag("title");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);

        TextView subtitleView = new TextView(context);
        subtitleView.setTag("subtitle");
        subtitleView.setTextColor(Color.rgb(156, 163, 175));
        subtitleView.setTextSize(11);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);

        TextView pathView = new TextView(context);
        pathView.setTag("path");
        pathView.setTextColor(Color.rgb(107, 114, 128)); // 弱化文本颜色
        pathView.setTextSize(11);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);

        titleArea.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        titleArea.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
        titleArea.addView(pathView, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titleArea, new LinearLayout.LayoutParams(-1, -2));

        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // 静态添加最近输入框，通过 updateRecentInput 控制可见性
        TextView recentView = recentInputBox(context, "");
        recentView.setTag("recent_box");
        content.addView(recentView, new LinearLayout.LayoutParams(-1, -2));

        row.addView(content, new FrameLayout.LayoutParams(-1, -2));

        TextView closeBtn = new TextView(context);
        closeBtn.setTag("close");
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.rgb(107, 114, 128)); // 弱化关闭按钮颜色，避免视觉喧宾夺主
        closeBtn.setTextSize(14);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(UIUtils.dp(context, 32), UIUtils.dp(context, 32));
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.setMargins(0, UIUtils.dp(context, 4), UIUtils.dp(context, 4), 0);
        row.addView(closeBtn, closeLp);

        return row;
    }

    public static void updateSessionRow(final SessionRowActions actions, View row, JSONObject session, final ServerConfig server) {
        TextView titleView = row.findViewWithTag("title");
        TextView subtitleView = row.findViewWithTag("subtitle");
        TextView pathView = row.findViewWithTag("path");
        TextView recentView = row.findViewWithTag("recent_box");
        TextView closeBtn = row.findViewWithTag("close");

        String id = session.optString("id");
        row.setTag(id); // 绑定 Tag 以便差分查找

        String rawTermTitle = session.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = session.optString("name", "").trim();
        final String createdAt = session.optString("createdAt", "").trim();
        final String instanceId = session.optString("instanceId", "").trim();
        String cwd = session.optString("cwd", "");

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
        if (pathView != null) {
            pathView.setText(cwd); // 移除彩色 Emoji
        }
        if (recentView != null) {
            updateRecentInput(recentView, session);
        }

        row.setOnClickListener((v) -> actions.openSession(server, id, termTitle, nameText, createdAt, instanceId));

        row.setOnLongClickListener((v) -> {
            actions.renameSession(server, id, nameText);
            return true;
        });

        if (closeBtn != null) {
            closeBtn.setOnClickListener((v) -> actions.closeSession(server, id));
        }
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
        view.setTextColor(Color.rgb(52, 211, 153));
        view.setTextSize(11);
        view.setTypeface(Typeface.MONOSPACE);
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(UIUtils.dp(context, 8), UIUtils.dp(context, 6), UIUtils.dp(context, 8), UIUtils.dp(context, 6));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(10, 10, 12));
        gd.setCornerRadius(UIUtils.dp(context, 4));
        view.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, UIUtils.dp(context, 8), 0, 0);
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
