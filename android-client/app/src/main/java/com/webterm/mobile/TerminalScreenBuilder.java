package com.webterm.mobile;

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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

final class TerminalScreenBuilder {
    private TerminalScreenBuilder() {}

    static Result build(
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
        root.setBackgroundColor(Color.BLACK);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);

        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 6), UIUtils.dp(activity, 8), UIUtils.dp(activity, 6));
        topBar.setBackgroundColor(Color.rgb(30, 30, 36));

        ImageButton sessions = new ImageButton(activity);
        sessions.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        sessions.setColorFilter(Color.rgb(243, 244, 246));
        sessions.setBackground(iconButtonBackground(activity, UIUtils.dp(activity, 20)));
        sessions.setPadding(0, 0, 0, 0);
        sessions.setOnClickListener((v) -> onBack.run());

        TextView title = new TextView(activity);
        title.setText(headerTitle);
        title.setTextColor(Color.rgb(243, 244, 246));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);

        TextView subtitle = new TextView(activity);
        subtitle.setText(headerSubtitle);
        subtitle.setTextColor(Color.rgb(156, 163, 175));
        subtitle.setTextSize(11);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout statusContainer = new LinearLayout(activity);
        statusContainer.setOrientation(LinearLayout.HORIZONTAL);
        statusContainer.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton retryButton = new ImageButton(activity);
        retryButton.setImageResource(com.webterm.mobile.R.drawable.ic_refresh);
        retryButton.setColorFilter(Color.rgb(243, 244, 246));
        retryButton.setVisibility(View.GONE);
        retryButton.setBackground(iconButtonBackground(activity, UIUtils.dp(activity, 18)));
        retryButton.setPadding(0, 0, 0, 0);
        retryButton.setOnClickListener((v) -> onRetry.run());

        View statusIndicator = new View(activity);
        GradientDrawable indicatorBg = new GradientDrawable();
        indicatorBg.setShape(GradientDrawable.OVAL);
        indicatorBg.setColor(Color.rgb(239, 68, 68));
        statusIndicator.setBackground(indicatorBg);

        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 36), UIUtils.dp(activity, 36));
        retryLp.setMargins(0, 0, UIUtils.dp(activity, 10), 0);
        statusContainer.addView(retryButton, retryLp);

        LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 12), UIUtils.dp(activity, 12));
        indicatorLp.setMargins(0, 0, UIUtils.dp(activity, 6), 0);
        statusContainer.addView(statusIndicator, indicatorLp);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(UIUtils.dp(activity, 10), 0, UIUtils.dp(activity, 8), 0);
        labels.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
        labels.addView(subtitle, new LinearLayout.LayoutParams(-1, 0, 1));

        topBar.addView(sessions, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));
        topBar.addView(labels, new LinearLayout.LayoutParams(0, UIUtils.dp(activity, 44), 1));
        topBar.addView(statusContainer, new LinearLayout.LayoutParams(-2, -2));
        content.addView(topBar, new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 54)));

        TerminalView terminalView = new TerminalView(activity, null);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setTextSize(fontSize);
        terminalView.setTypeface(typeface);
        terminalView.setTerminalViewClient(terminalViewClient);

        FrameLayout terminalViewport = new FrameLayout(activity);
        terminalViewport.setClipChildren(true);
        terminalViewport.setClipToPadding(true);
        terminalViewport.setBackgroundColor(Color.BLACK);
        terminalViewport.addView(terminalView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(terminalViewport, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        View quickBar = createQuickBar(activity, terminalView, onCtrl, textSender);
        root.addView(quickBar, new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 92)));

        Result result = new Result();
        result.root = root;
        result.terminalView = terminalView;
        result.terminalViewport = terminalViewport;
        result.quickBar = quickBar;
        result.title = title;
        result.subtitle = subtitle;
        result.retryButton = retryButton;
        result.statusIndicator = statusIndicator;
        return result;
    }

    private static Drawable iconButtonBackground(Activity activity, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(radius);
        bg.setStroke(UIUtils.dp(activity, 1), Color.rgb(55, 65, 81));
        return bg;
    }

    private static View createQuickBar(Activity activity, TerminalView terminalView, Runnable onCtrl, TextSender textSender) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(UIUtils.dp(activity, 8), UIUtils.dp(activity, 7), UIUtils.dp(activity, 8), UIUtils.dp(activity, 7));
        bar.setBackgroundColor(Color.rgb(20, 20, 24));

        LinearLayout firstRow = quickBarRow(activity);
        LinearLayout secondRow = quickBarRow(activity);
        addKey(activity, terminalView, firstRow, "Ctrl", onCtrl);
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

    private static void addKey(Activity activity, TerminalView terminalView, LinearLayout row, String label, Runnable action) {
        Button button = new Button(activity);
        button.setFocusable(false);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(Color.rgb(243, 244, 246));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(UIUtils.dp(activity, 4), 0, UIUtils.dp(activity, 4), 0);
        button.setBackground(quickBarButtonBackground(activity, false));
        button.setOnClickListener((v) -> {
            if (!terminalView.isFocused()) terminalView.requestFocus();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(UIUtils.dp(activity, 3), UIUtils.dp(activity, 3), UIUtils.dp(activity, 3), UIUtils.dp(activity, 3));
        row.addView(button, lp);
    }

    private static Drawable quickBarButtonBackground(Activity activity, boolean active) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setColor(Color.rgb(15, 15, 18));
        content.setStroke(UIUtils.dp(activity, 1), active ? Color.rgb(99, 102, 241) : Color.rgb(55, 65, 81));
        content.setCornerRadius(UIUtils.dp(activity, 4));

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(UIUtils.dp(activity, 4));

        ColorStateList colorStateList = ColorStateList.valueOf(Color.argb(36, 243, 244, 246));
        return new RippleDrawable(colorStateList, content, mask);
    }

    interface TextSender {
        void send(String text);
    }

    static final class Result {
        LinearLayout root;
        TerminalView terminalView;
        View terminalViewport;
        View quickBar;
        TextView title;
        TextView subtitle;
        ImageButton retryButton;
        View statusIndicator;
    }
}
