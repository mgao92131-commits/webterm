package com.webterm.mobile;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SessionRowHelper {

    public static void addSessionRow(final MainActivity activity, JSONObject session, final ServerConfig server, LinearLayout subList) {
        String id = session.optString("id");
        String rawTermTitle = session.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = session.optString("name", "").trim();
        String size = session.optInt("cols", 0) + "x" + session.optInt("rows", 0);
        String cwd = session.optString("cwd", "");

        LinearLayout row = new LinearLayout(activity);
        row.setTag(id); // 绑定 Tag 以便差分查找
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UIUtils.dp(activity, 14), UIUtils.dp(activity, 12), UIUtils.dp(activity, 14), UIUtils.dp(activity, 12));
        row.setBackground(UIUtils.panelBackground(activity));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleArea = new LinearLayout(activity);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(activity);
        titleView.setTag("title");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);

        TextView subtitleView = new TextView(activity);
        subtitleView.setTag("subtitle");
        subtitleView.setTextColor(Color.rgb(156, 163, 175));
        subtitleView.setTextSize(11);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);

        TextView pathView = new TextView(activity);
        pathView.setTag("path");
        pathView.setTextColor(Color.rgb(107, 114, 128)); // 弱化文本颜色
        pathView.setTextSize(11);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        pathView.setText(cwd); // 移除彩色 Emoji

        if (!nameText.isEmpty()) {
            titleView.setText(nameText);
            subtitleView.setText(termTitle);
            subtitleView.setVisibility(View.VISIBLE);
        } else {
            titleView.setText(termTitle);
            subtitleView.setVisibility(View.GONE);
        }
        titleArea.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        titleArea.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
        titleArea.addView(pathView, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titleArea, new LinearLayout.LayoutParams(0, -2, 1));

        TextView closeBtn = new TextView(activity);
        closeBtn.setTag("close");
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.rgb(107, 114, 128)); // 弱化关闭按钮颜色，避免视觉喧宾夺主
        closeBtn.setTextSize(16);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 4), UIUtils.dp(activity, 8), UIUtils.dp(activity, 4));
        header.addView(closeBtn, new LinearLayout.LayoutParams(-2, -2));

        row.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // 静态添加最近输入框，通过 updateRecentInput 控制可见性
        TextView recentView = recentInputBox(activity, "");
        recentView.setTag("recent_box");
        row.addView(recentView, new LinearLayout.LayoutParams(-1, -2));
        updateRecentInput(recentView, session);

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, UIUtils.dp(activity, 8), 0, 0);

        footer.addView(createChip(activity, "id: " + id)); // 移除彩色 Emoji
        TextView sizeChip = createChip(activity, size);
        sizeChip.setTag("size_chip");
        footer.addView(sizeChip); // 移除彩色 Emoji 并直接展示尺寸

        row.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        row.setOnClickListener((v) -> activity.showTerminal(server.url, server.cookie, id, termTitle, nameText));

        row.setOnLongClickListener((v) -> {
            activity.showRenameDialog(server, id, nameText);
            return true;
        });

        closeBtn.setOnClickListener((v) -> {
            activity.showCloseConfirmDialog(server, id);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UIUtils.dp(activity, 12));
        subList.addView(row, lp);
    }

    public static void updateSessionRow(final MainActivity activity, View row, JSONObject session, final ServerConfig server) {
        TextView titleView = row.findViewWithTag("title");
        TextView subtitleView = row.findViewWithTag("subtitle");
        TextView pathView = row.findViewWithTag("path");
        TextView recentView = row.findViewWithTag("recent_box");
        TextView sizeChip = row.findViewWithTag("size_chip");

        String id = session.optString("id");
        String rawTermTitle = session.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = session.optString("name", "").trim();
        String size = session.optInt("cols", 0) + "x" + session.optInt("rows", 0);
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
            pathView.setText(cwd);
        }
        if (recentView != null) {
            updateRecentInput(recentView, session);
        }
        if (sizeChip != null) {
            sizeChip.setText(size);
        }

        // 重新绑定点击/长按闭包以同步最新标题
        row.setOnClickListener((v) -> activity.showTerminal(server.url, server.cookie, id, termTitle, nameText));
        row.setOnLongClickListener((v) -> {
            activity.showRenameDialog(server, id, nameText);
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

    private static TextView createChip(android.content.Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.rgb(107, 114, 128)); // 弱化标签文字颜色
        view.setTextSize(10);
        view.setPadding(UIUtils.dp(context, 6), UIUtils.dp(context, 2), UIUtils.dp(context, 6), UIUtils.dp(context, 2));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(12, 107, 114, 128)); // 弱化半透明背景
        gd.setCornerRadius(UIUtils.dp(context, 10));
        view.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, UIUtils.dp(context, 6), 0);
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
