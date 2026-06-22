package com.webterm.mobile;

import android.app.AlertDialog;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class SettingsDialogHelper {

    public static void show(final Host host) {
        Activity activity = host.activity();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5)
        );

        container.setBackground(UIUtils.dialogBackground(activity));

        TextView titleView = new TextView(activity);
        titleView.setText("终端显示设置");
        titleView.setTextColor(DesignTokens.TEXT_PRIMARY);
        titleView.setTextSize(DesignTokens.TEXT_DIALOG_TITLE);
        titleView.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        titleView.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_5));
        container.addView(UIUtils.dialogTitleRow(
            activity,
            com.webterm.mobile.R.drawable.ic_settings,
            "终端显示设置",
            DesignTokens.TEXT_PRIMARY,
            DesignTokens.TEXT_SECONDARY
        ));

        // ----------------- 字号设置 (Stepper) -----------------
        LinearLayout fontSizeRow = new LinearLayout(activity);
        fontSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        fontSizeRow.setGravity(Gravity.CENTER_VERTICAL);
        fontSizeRow.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_4));

        TextView sizeLabel = new TextView(activity);
        sizeLabel.setText("终端字号");
        sizeLabel.setTextColor(DesignTokens.TEXT_PRIMARY);
        sizeLabel.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        fontSizeRow.addView(sizeLabel, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout sizeStepper = new LinearLayout(activity);
        sizeStepper.setOrientation(LinearLayout.HORIZONTAL);
        sizeStepper.setGravity(Gravity.CENTER_VERTICAL);

        Button sizeDecBtn = new Button(activity);
        sizeDecBtn.setText("-");
        UIUtils.styleStepperButton(activity, sizeDecBtn);

        TextView sizeValText = new TextView(activity);
        sizeValText.setText(String.valueOf(host.getSavedFontSize()));
        sizeValText.setTextColor(DesignTokens.TEXT_PRIMARY);
        sizeValText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        sizeValText.setTypeface(DesignTokens.fontGeistMono(activity));
        sizeValText.setGravity(Gravity.CENTER);

        Button sizeIncBtn = new Button(activity);
        sizeIncBtn.setText("+");
        UIUtils.styleStepperButton(activity, sizeIncBtn);

        sizeDecBtn.setOnClickListener((v) -> {
            int cur = host.getSavedFontSize();
            if (cur > 10) {
                int next = cur - 1;
                host.saveFontSize(next);
                sizeValText.setText(String.valueOf(next));
                host.applyTerminalFontSize(next);
            }
        });

        sizeIncBtn.setOnClickListener((v) -> {
            int cur = host.getSavedFontSize();
            if (cur < 72) {
                int next = cur + 1;
                host.saveFontSize(next);
                sizeValText.setText(String.valueOf(next));
                host.applyTerminalFontSize(next);
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 36), UIUtils.dp(activity, 36));
        LinearLayout.LayoutParams sizeValLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 60), -2);

        sizeStepper.addView(sizeDecBtn, btnLp);
        sizeStepper.addView(sizeValText, sizeValLp);
        sizeStepper.addView(sizeIncBtn, btnLp);
        fontSizeRow.addView(sizeStepper);
        container.addView(fontSizeRow);

        // ----------------- 字体切换 (Stepper) -----------------
        LinearLayout fontTypeRow = new LinearLayout(activity);
        fontTypeRow.setOrientation(LinearLayout.HORIZONTAL);
        fontTypeRow.setGravity(Gravity.CENTER_VERTICAL);
        fontTypeRow.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_5));

        TextView fontLabel = new TextView(activity);
        fontLabel.setText("终端字体");
        fontLabel.setTextColor(DesignTokens.TEXT_PRIMARY);
        fontLabel.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        fontTypeRow.addView(fontLabel, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout fontStepper = new LinearLayout(activity);
        fontStepper.setOrientation(LinearLayout.HORIZONTAL);
        fontStepper.setGravity(Gravity.CENTER_VERTICAL);

        Button fontPrevBtn = new Button(activity);
        fontPrevBtn.setText("◀");
        UIUtils.styleStepperButton(activity, fontPrevBtn);

        TextView fontValText = new TextView(activity);
        fontValText.setText(host.getFontDisplayName(host.getSavedFontType()));
        fontValText.setTextColor(DesignTokens.TEXT_PRIMARY);
        fontValText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        fontValText.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        fontValText.setGravity(Gravity.CENTER);

        Button fontNextBtn = new Button(activity);
        fontNextBtn.setText("▶");
        UIUtils.styleStepperButton(activity, fontNextBtn);

        final String[] fontOptions = {"monospace", "sans-serif", "serif", "default"};

        fontPrevBtn.setOnClickListener((v) -> {
            String cur = host.getSavedFontType();
            int idx = -1;
            for (int i = 0; i < fontOptions.length; i++) {
                if (fontOptions[i].equals(cur)) {
                    idx = i;
                    break;
                }
            }
            int nextIdx = (idx - 1 + fontOptions.length) % fontOptions.length;
            String next = fontOptions[nextIdx];
            host.saveFontType(next);
            fontValText.setText(host.getFontDisplayName(next));
            host.applyTerminalTypeface(host.getTypefaceByName(next));
        });

        fontNextBtn.setOnClickListener((v) -> {
            String cur = host.getSavedFontType();
            int idx = -1;
            for (int i = 0; i < fontOptions.length; i++) {
                if (fontOptions[i].equals(cur)) {
                    idx = i;
                    break;
                }
            }
            int nextIdx = (idx + 1) % fontOptions.length;
            String next = fontOptions[nextIdx];
            host.saveFontType(next);
            fontValText.setText(host.getFontDisplayName(next));
            host.applyTerminalTypeface(host.getTypefaceByName(next));
        });

        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 100), -2);

        fontStepper.addView(fontPrevBtn, btnLp);
        fontStepper.addView(fontValText, valLp);
        fontStepper.addView(fontNextBtn, btnLp);
        fontTypeRow.addView(fontStepper);
        container.addView(fontTypeRow);

        // ----------------- 关闭/确定按钮 -----------------
        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);

        Button closeBtn = new Button(activity);
        closeBtn.setText("完成");
        UIUtils.styleDialogButton(activity, closeBtn, true);

        btnBar.addView(closeBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 40)));
        container.addView(btnBar);

        builder.setView(container);
        final AlertDialog dialog = builder.create();
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        closeBtn.setOnClickListener((v) -> dialog.dismiss());
    }

    interface Host {
        Activity activity();
        int getSavedFontSize();
        String getSavedFontType();
        Typeface getTypefaceByName(String type);
        String getFontDisplayName(String fontType);
        void saveFontSize(int size);
        void saveFontType(String type);
        void applyTerminalFontSize(int size);
        void applyTerminalTypeface(Typeface typeface);
    }
}
