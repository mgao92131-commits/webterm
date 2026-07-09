package com.webterm.feature.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;
import com.webterm.ui.common.StatusIndicatorView;

import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

public final class TerminalScreenBuilder {
    private TerminalScreenBuilder() {}

    public static Result build(
        Activity activity,
        String headerTitle,
        String headerSubtitle,
        int fontSize,
        Typeface typeface,
        TerminalViewClient terminalViewClient,
        Runnable onBack,
        Runnable onRetry,
        Runnable onCtrl,
        TextSender textSender
    ) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.TERMINAL_BG);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(DesignTokens.TERMINAL_BG);

        // 统一顶栏（bg_secondary 背景 + 分割线）
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_TERMINAL);
        LinearLayout topBar = UIUtils.topbarFromWrapper(topbarWrapper);

        ImageButton sessions = new ImageButton(activity);
        sessions.setImageResource(com.webterm.ui.common.R.drawable.ic_arrow_back);
        sessions.setColorFilter(DesignTokens.TEXT_PRIMARY);
        sessions.setBackground(UIUtils.iconButtonBackground(activity, 18));
        sessions.setPadding(0, 0, 0, 0);
        sessions.setOnClickListener((v) -> onBack.run());
        topBar.addView(sessions, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        // 标题区域
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText(headerTitle);
        title.setTextColor(DesignTokens.TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextSize(DesignTokens.TEXT_HEADING_SIZE);
        title.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 副标题容器：[指示灯] + [副标题]
        LinearLayout subtitleContainer = new LinearLayout(activity);
        subtitleContainer.setOrientation(LinearLayout.HORIZONTAL);
        subtitleContainer.setGravity(Gravity.CENTER_VERTICAL);

        StatusIndicatorView statusIndicator = new StatusIndicatorView(activity);
        LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(
            UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE),
            UIUtils.dp(activity, DesignTokens.STATUS_DOT_SIZE));
        indicatorLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        subtitleContainer.addView(statusIndicator, indicatorLp);

        TextView subtitle = new TextView(activity);
        subtitle.setText(headerSubtitle);
        subtitle.setTextColor(DesignTokens.TEXT_SECONDARY);
        subtitle.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitleContainer.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        labels.addView(subtitleContainer, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        // 右侧按钮组
        LinearLayout buttonGroup = new LinearLayout(activity);
        buttonGroup.setOrientation(LinearLayout.HORIZONTAL);
        buttonGroup.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton retryButton = new ImageButton(activity);
        retryButton.setImageResource(com.webterm.ui.common.R.drawable.ic_refresh);
        retryButton.setColorFilter(DesignTokens.TEXT_PRIMARY);
        retryButton.setVisibility(View.GONE);
        retryButton.setBackground(UIUtils.iconButtonBackground(activity, 18));
        retryButton.setPadding(0, 0, 0, 0);
        retryButton.setOnClickListener((v) -> onRetry.run());

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.TOPBAR_ICON_SIZE), UIUtils.dp(activity, DesignTokens.TOPBAR_ICON_SIZE));
        buttonGroup.addView(retryButton, btnLp);
        topBar.addView(buttonGroup, new LinearLayout.LayoutParams(-2, -2));
        content.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        TerminalView terminalView = new TerminalView(activity, null);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setTextSize(fontSize);
        terminalView.setTypeface(typeface);
        terminalView.setTerminalViewClient(terminalViewClient);

        FrameLayout terminalViewport = new FrameLayout(activity);
        terminalViewport.setClipChildren(true);
        terminalViewport.setClipToPadding(true);
        terminalViewport.setBackgroundColor(DesignTokens.TERMINAL_BG);
        terminalViewport.addView(terminalView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout reconnectOverlay = new LinearLayout(activity);
        reconnectOverlay.setOrientation(LinearLayout.VERTICAL);
        reconnectOverlay.setGravity(Gravity.CENTER);
        reconnectOverlay.setBackgroundColor(DesignTokens.OVERLAY);
        reconnectOverlay.setVisibility(View.GONE);
        reconnectOverlay.setClickable(true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5)
        );

        card.setBackground(UIUtils.dialogBackground(activity));

        // 警告图标：ImageView (ic_warning)
        ImageView warnIcon = new ImageView(activity);
        warnIcon.setImageResource(com.webterm.feature.terminal.R.drawable.ic_warning);
        warnIcon.setColorFilter(DesignTokens.WARNING);
        warnIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                UIUtils.dp(activity, 40), UIUtils.dp(activity, 40));
        warnLp.gravity = Gravity.CENTER_HORIZONTAL;
        warnLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2));
        card.addView(warnIcon, warnLp);

        TextView tipText = new TextView(activity);
        tipText.setText("与服务器连接已断开");
        tipText.setTextColor(DesignTokens.TEXT_PRIMARY);
        tipText.setTextSize(DesignTokens.TEXT_HEADING_SIZE);
        tipText.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        tipText.setGravity(Gravity.CENTER);
        tipText.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_4));

        Button reconnectBtn = new Button(activity);
        reconnectBtn.setText("重新连接");
        reconnectBtn.setAllCaps(false);
        // 重新连接按钮改用强调色（设计系统统一）
        UIUtils.styleDialogButton(activity, reconnectBtn, true);

        reconnectBtn.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_4), UIUtils.dp(activity, DesignTokens.SPACE_2), UIUtils.dp(activity, DesignTokens.SPACE_4), UIUtils.dp(activity, DesignTokens.SPACE_2));
        reconnectBtn.setOnClickListener((v) -> onRetry.run());

        card.addView(tipText);
        card.addView(reconnectBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 120), UIUtils.dp(activity, 38)));

        reconnectOverlay.addView(card, new LinearLayout.LayoutParams(UIUtils.dp(activity, 240), -2));
        terminalViewport.addView(reconnectOverlay, new FrameLayout.LayoutParams(-1, -1));

        content.addView(terminalViewport, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        Button[] outCtrlButton = new Button[1];
        View quickBar = createQuickBar(activity, terminalView, onCtrl, textSender, outCtrlButton);
        root.addView(quickBar, new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, DesignTokens.QUICKBAR_HEIGHT)));

        Result result = new Result();
        result.root = root;
        result.terminalView = terminalView;
        result.terminalViewport = terminalViewport;
        result.quickBar = quickBar;
        result.title = title;
        result.subtitle = subtitle;
        result.retryButton = retryButton;
        result.statusIndicator = statusIndicator;
        result.ctrlButton = outCtrlButton[0];
        result.reconnectOverlay = reconnectOverlay;
        return result;
    }

    private static Drawable iconButtonBackground(Activity activity, int radius) {
        return UIUtils.iconButtonBackground(activity, radius);
    }

    private static View createQuickBar(Activity activity, TerminalView terminalView, Runnable onCtrl, TextSender textSender, Button[] outCtrlButton) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2)
        );
        bar.setBackgroundColor(DesignTokens.BG_SECONDARY);

        LinearLayout firstRow = quickBarRow(activity);
        LinearLayout secondRow = quickBarRow(activity);
        Button ctrlButton = addKey(activity, terminalView, firstRow, "Ctrl", onCtrl);
        if (outCtrlButton != null && outCtrlButton.length > 0) {
            outCtrlButton[0] = ctrlButton;
        }
        addKey(activity, terminalView, firstRow, "Esc", () -> textSender.send("\033"));
        addKey(activity, terminalView, firstRow, "C-l", () -> textSender.send("\014"));
        addKey(activity, terminalView, firstRow, "C-d", () -> textSender.send("\004"));
        addKey(activity, terminalView, firstRow, "C-c", () -> textSender.send("\003"));
        addKey(activity, terminalView, firstRow, "S-Tab", () -> textSender.send("\033[Z"));
        addKey(activity, terminalView, firstRow, "Tab", () -> textSender.send("\t"));

        addKey(activity, terminalView, secondRow, "/", () -> textSender.send("/"));
        addKey(activity, terminalView, secondRow, "PgUp", () -> textSender.send("\033[5~"));
        addKey(activity, terminalView, secondRow, "PgDn", () -> textSender.send("\033[6~"));
        addKey(activity, terminalView, secondRow, "←", () -> textSender.send("\033[D"));
        addKey(activity, terminalView, secondRow, "→", () -> textSender.send("\033[C"));
        addKey(activity, terminalView, secondRow, "↓", () -> textSender.send("\033[B"));
        addKey(activity, terminalView, secondRow, "↑", () -> textSender.send("\033[A"));
        bar.addView(firstRow, new LinearLayout.LayoutParams(-1, 0, 1));
        bar.addView(secondRow, new LinearLayout.LayoutParams(-1, 0, 1));
        return bar;
    }

    private static LinearLayout quickBarRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static Button addKey(Activity activity, TerminalView terminalView, LinearLayout row, String label, Runnable action) {
        Button button = new Button(activity);
        button.setFocusable(false);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        button.setTextColor(DesignTokens.TEXT_PRIMARY);
        button.setTypeface(DesignTokens.fontGeistMono(activity));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_1), 0, UIUtils.dp(activity, DesignTokens.SPACE_1), 0);
        button.setBackground(quickBarButtonBackground(activity, false));
        button.setOnClickListener((v) -> {
            if (!terminalView.isFocused()) terminalView.requestFocus();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(UIUtils.dp(activity, 3), UIUtils.dp(activity, 3), UIUtils.dp(activity, 3), UIUtils.dp(activity, 3));
        row.addView(button, lp);
        return button;
    }

    /**
     * 快捷栏按钮背景。
     *   - 未激活：BG_PRIMARY 填充 + BORDER_PRIMARY 边框
     *   - Ctrl 激活：BORDER_HOVER 边框（hover 态强调），与设计系统强调色 #10B981 对齐
     *   - 圆角 4dp（与 Web 端 radius-sm 一致）
     */
    private static Drawable quickBarButtonBackground(Activity activity, boolean active) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setColor(DesignTokens.BG_PRIMARY);
        content.setStroke(
            UIUtils.dp(activity, 1),
            active ? DesignTokens.ACCENT : DesignTokens.BORDER_PRIMARY
        );
        content.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(android.graphics.Color.WHITE);
        mask.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));

        // 水波纹用主文字色 15% 透明
        ColorStateList colorStateList = ColorStateList.valueOf(
            DesignTokens.withAlpha(DesignTokens.TEXT_PRIMARY, 0x24) // 14%
        );
        return new RippleDrawable(colorStateList, content, mask);
    }

    public interface TextSender {
        void send(String text);
    }

    public static void updateCtrlButtonState(Activity activity, Button ctrlButton, boolean active) {
        if (ctrlButton != null) {
            ctrlButton.setBackground(quickBarButtonBackground(activity, active));
        }
    }

    public static final class Result {
        public LinearLayout root;
        public TerminalView terminalView;
        public View terminalViewport;
        public View quickBar;
        public TextView title;
        public TextView subtitle;
        public ImageButton retryButton;
        public StatusIndicatorView statusIndicator;
        public Button ctrlButton;
        public View reconnectOverlay;
    }
}
