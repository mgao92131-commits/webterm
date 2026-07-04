package com.webterm.mobile.ui.dialog;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;

public final class RenameSessionDialogHelper {
    private RenameSessionDialogHelper() {}

    public static void show(ActivityHost host, String oldName, SubmitCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host.activity());

        LinearLayout container = new LinearLayout(host.activity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(
            UIUtils.dp(host.activity(), DesignTokens.SPACE_5),
            UIUtils.dp(host.activity(), DesignTokens.SPACE_5),
            UIUtils.dp(host.activity(), DesignTokens.SPACE_5),
            UIUtils.dp(host.activity(), DesignTokens.SPACE_5)
        );

        container.setBackground(UIUtils.dialogBackground(host.activity()));

        container.addView(UIUtils.dialogTitleRow(
            host.activity(),
            com.webterm.mobile.R.drawable.ic_edit,
            "重命名会话",
            DesignTokens.TEXT_PRIMARY,
            DesignTokens.ACCENT
        ));

        EditText input = UIUtils.createInput(host.activity(), "输入新名称");
        input.setText(oldName);
        container.addView(input, UIUtils.matchWrap(host.activity()));

        LinearLayout btnBar = new LinearLayout(host.activity());
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);
        btnBar.setPadding(0, UIUtils.dp(host.activity(), DesignTokens.SPACE_2), 0, 0);

        Button cancelBtn = new Button(host.activity());
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(host.activity(), cancelBtn, false);

        Button submitBtn = new Button(host.activity());
        submitBtn.setText("保存");
        UIUtils.styleDialogButton(host.activity(), submitBtn, true);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(host.activity(), 100), UIUtils.dp(host.activity(), 40));
        btnLp.setMargins(UIUtils.dp(host.activity(), DesignTokens.SPACE_3), 0, 0, 0);

        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(host.activity(), 80), UIUtils.dp(host.activity(), 40)));
        btnBar.addView(submitBtn, btnLp);
        container.addView(btnBar);

        builder.setView(container);
        builder.setCancelable(true);
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            );
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        input.post(() -> {
            input.requestFocus();
            input.selectAll();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                host.activity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });

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

    public interface ActivityHost {
        android.app.Activity activity();
    }

    public interface SubmitCallback {
        void onSubmit(String newName, AlertDialog dialog);
    }
}
