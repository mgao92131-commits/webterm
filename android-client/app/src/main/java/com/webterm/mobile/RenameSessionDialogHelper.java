package com.webterm.mobile;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class RenameSessionDialogHelper {
    private RenameSessionDialogHelper() {}

    static void show(ActivityHost host, String oldName, SubmitCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host.activity());

        LinearLayout container = new LinearLayout(host.activity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UIUtils.dp(host.activity(), 24), UIUtils.dp(host.activity(), 24), UIUtils.dp(host.activity(), 24), UIUtils.dp(host.activity(), 24));

        android.graphics.drawable.GradientDrawable containerBg = new android.graphics.drawable.GradientDrawable();
        containerBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        containerBg.setColor(Color.rgb(30, 30, 36));
        containerBg.setCornerRadius(UIUtils.dp(host.activity(), 12));
        containerBg.setStroke(UIUtils.dp(host.activity(), 1), Color.rgb(55, 65, 81));
        container.setBackground(containerBg);

        TextView titleView = new TextView(host.activity());
        titleView.setText("✏️ 重命名会话");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, UIUtils.dp(host.activity(), 16));
        container.addView(titleView);

        EditText input = UIUtils.createInput(host.activity(), "输入新名称");
        input.setText(oldName);
        input.requestFocus();
        container.addView(input, UIUtils.matchWrap(host.activity()));

        LinearLayout btnBar = new LinearLayout(host.activity());
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);
        btnBar.setPadding(0, UIUtils.dp(host.activity(), 8), 0, 0);

        Button cancelBtn = new Button(host.activity());
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(host.activity(), cancelBtn, false);

        Button submitBtn = new Button(host.activity());
        submitBtn.setText("保存");
        UIUtils.styleDialogButton(host.activity(), submitBtn, true);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(host.activity(), 100), UIUtils.dp(host.activity(), 40));
        btnLp.setMargins(UIUtils.dp(host.activity(), 12), 0, 0, 0);

        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(host.activity(), 80), UIUtils.dp(host.activity(), 40)));
        btnBar.addView(submitBtn, btnLp);
        container.addView(btnBar);

        builder.setView(container);
        final AlertDialog dialog = builder.create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        cancelBtn.setOnClickListener((v) -> dialog.dismiss());
        submitBtn.setOnClickListener((v) -> {
            String newName = input.getText().toString().trim();
            if (newName.equals(oldName)) {
                dialog.dismiss();
                return;
            }
            submitBtn.setEnabled(false);
            cancelBtn.setEnabled(false);
            callback.onSubmit(newName, dialog);
        });
    }

    interface ActivityHost {
        android.app.Activity activity();
    }

    interface SubmitCallback {
        void onSubmit(String newName, AlertDialog dialog);
    }
}
