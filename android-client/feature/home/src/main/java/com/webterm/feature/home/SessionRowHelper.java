package com.webterm.feature.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
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
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setTag("title");
        titleView.setTextColor(DesignTokens.TEXT_PRIMARY);
        titleView.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        titleView.setTypeface(DesignTokens.fontGeistSansSemibold(context));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);

        TextView agentChip = new TextView(context);
        agentChip.setTag("agent_chip");
        agentChip.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        agentChip.setSingleLine(true);
        agentChip.setEllipsize(TextUtils.TruncateAt.END);
        agentChip.setVisibility(View.GONE);

        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(agentChip, new LinearLayout.LayoutParams(-2, -2));

        content.addView(header, new LinearLayout.LayoutParams(-1, -2));



        // 最近命令行
        TextView commandView = new TextView(context);
        commandView.setTag("command_box");
        commandView.setTextColor(DesignTokens.TEXT_SECONDARY);
        commandView.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        commandView.setTypeface(DesignTokens.fontGeistMono(context));
        commandView.setSingleLine(true);
        commandView.setEllipsize(TextUtils.TruncateAt.END);
        commandView.setVisibility(View.GONE);
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(-1, -2);
        cmdLp.setMargins(0, UIUtils.dp(context, DesignTokens.SPACE_2), 0, 0);
        content.addView(commandView, cmdLp);

        row.addView(content, new FrameLayout.LayoutParams(-1, -2));

        return row;
    }

    public static void updateSessionRow(final SessionRowActions actions, View row, JSONObject session, final ServerConfig server) {
        TextView titleView = row.findViewWithTag("title");
        TextView agentChip = row.findViewWithTag("agent_chip");
        TextView commandView = row.findViewWithTag("command_box");

        String id = session.optString("id");
        row.setTag(id); // 绑定 Tag 以便差分查找

        String rawTermTitle = session.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String createdAt = session.optString("createdAt", "").trim();
        final String instanceId = session.optString("instanceId", "").trim();
        final String cwd = session.optString("cwd", "").trim();

        if (titleView != null) {
            titleView.setText(termTitle);
        }

        updateAgentChip(agentChip, session);
        updateCommandLine(commandView, session);

        row.setOnClickListener((v) -> actions.openSession(server, id, termTitle, createdAt, instanceId, cwd));
    }

    private static void updateAgentChip(TextView agentChip, JSONObject session) {
        if (agentChip == null) return;
        JSONObject notification = session.optJSONObject("notification");
        Context context = agentChip.getContext();

        if (notification != null) {
            String source = notification.optString("source", "").trim();
            String importance = notification.optString("importance", "quiet").trim();
            if (source.isEmpty()) {
                agentChip.setVisibility(View.GONE);
                return;
            }
            agentChip.setText(source);
            agentChip.setTextColor(chipTextColor(importance));
            agentChip.setBackground(chipBackground(context, importance));
            agentChip.setPadding(
                UIUtils.dp(context, DesignTokens.SPACE_2),
                UIUtils.dp(context, 2),
                UIUtils.dp(context, DesignTokens.SPACE_2),
                UIUtils.dp(context, 2)
            );
            agentChip.setVisibility(View.VISIBLE);
            return;
        }

        agentChip.setVisibility(View.GONE);
    }

    private static void updateCommandLine(TextView commandView, JSONObject session) {
        if (commandView == null) return;
        String command = "";
        JSONObject notification = session.optJSONObject("notification");
        if (notification != null) {
            command = notification.optString("message", "").trim();
        }
        if (command.isEmpty()) {
            command = recentCommandText(session);
        }
        if (command.isEmpty()) {
            command = session.optString("lastCommand", "").trim();
        }
        if (command.isEmpty()) {
            commandView.setVisibility(View.GONE);
        } else {
            commandView.setText(command);
            commandView.setVisibility(View.VISIBLE);
        }
    }

    private static String recentCommandText(JSONObject session) {
        if (session.optBoolean("recentInputHidden", false)) {
            return "敏感输入已隐藏";
        }
        JSONArray lines = session.optJSONArray("recentInputLines");
        if (lines == null || lines.length() == 0) return "";
        String last = lines.optString(lines.length() - 1, "").trim();
        return last;
    }

    private static int chipTextColor(String importance) {
        switch (importance) {
            case "alert":
                return DesignTokens.DANGER;
            case "normal":
                return DesignTokens.ACCENT;
            case "quiet":
            default:
                // legacy 值（idle/running/error）按默认色处理
                return DesignTokens.TEXT_SECONDARY;
        }
    }

    private static android.graphics.drawable.Drawable chipBackground(Context context, String importance) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(UIUtils.dp(context, DesignTokens.RADIUS_SM));
        switch (importance) {
            case "alert":
                gd.setColor(DesignTokens.dangerBg());
                break;
            case "normal":
                gd.setColor(DesignTokens.accentBg());
                break;
            case "quiet":
            default:
                gd.setColor(DesignTokens.BG_TERTIARY);
        }
        return gd;
    }

}
