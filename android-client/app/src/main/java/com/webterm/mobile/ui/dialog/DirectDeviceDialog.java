package com.webterm.mobile.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.webterm.core.config.DirectDeviceAddressNormalizer;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;

/**
 * “添加直连设备”弹框：只输入设备地址、账户、密码三个字段。
 *
 * <p>地址解析交给 {@link DirectDeviceAddressNormalizer}；登录、去重与持久化由
 * {@link Host#submitDirectDevice} 的实现（AppFlowCoordinator）完成。提交期间禁用
 * 输入并显示“正在连接…”；失败时弹框保持打开并在正文显示错误，成功时关闭。
 */
public final class DirectDeviceDialog {

    /** 弹框宿主：提供 Activity 并执行真正的登录/保存。 */
    public interface Host {
        Activity activity();

        /** 规范化地址 + 账户 + 密码后登录并保存新设备，结果经 callback 回调（主线程）。 */
        void submitDirectDevice(String normalizedUrl, String username, String password, Callback callback);

        /** 编辑现有 Direct 设备：重新登录验证后替换旧配置（释放旧连接、建立新连接）。 */
        void updateDirectDevice(String oldConfigId, String normalizedUrl, String username, String password, Callback callback);
    }

    /** 提交结果回调。 */
    public interface Callback {
        void onSuccess(String displayName);

        void onError(String message);
    }

    private DirectDeviceDialog() {}

    /** 添加模式。 */
    public static void show(Host host) {
        showInternal(host, null);
    }

    /** 编辑模式：预填当前地址/账户/密码。 */
    public static void showForEdit(Host host, com.webterm.core.config.ServerConfig server) {
        showInternal(host, server);
    }

    private static void showInternal(Host host, com.webterm.core.config.ServerConfig editing) {
        Activity activity = host.activity();
        if (activity == null) return;
        final boolean isEdit = editing != null;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5),
            UIUtils.dp(activity, DesignTokens.SPACE_5));
        container.setBackground(UIUtils.dialogBackground(activity));

        container.addView(UIUtils.dialogTitleRow(
            activity,
            com.webterm.mobile.R.drawable.ic_add,
            isEdit ? "编辑直连设备" : "添加直连设备",
            DesignTokens.TEXT_PRIMARY,
            DesignTokens.TEXT_SECONDARY));

        TextView hint = new TextView(activity);
        hint.setText("请确保电脑上的 Agent 以 direct 模式启动，且手机与其处于同一局域网。");
        hint.setTextColor(DesignTokens.TEXT_TERTIARY);
        hint.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, UIUtils.dp(activity, DesignTokens.SPACE_4));
        container.addView(hint, hintLp);

        EditText addressInput = UIUtils.createInput(activity, "设备地址，如 192.168.1.20:8080");
        EditText usernameInput = UIUtils.createInput(activity, "账户");
        EditText passwordInput = UIUtils.createInput(activity, "密码");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (isEdit) {
            addressInput.setText(editing.getUrl());
            usernameInput.setText(editing.getUsername());
            passwordInput.setText(editing.getPassword());
        }

        int fieldGap = UIUtils.dp(activity, DesignTokens.SPACE_3);
        container.addView(addressInput, fieldLayoutParams(fieldGap));
        container.addView(usernameInput, fieldLayoutParams(fieldGap));
        container.addView(passwordInput, fieldLayoutParams(fieldGap));

        TextView errorText = new TextView(activity);
        errorText.setTextColor(0xFFEF4444);
        errorText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(-1, -2);
        errorLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);
        container.addView(errorText, errorLp);

        // 按钮栏：取消 / 连接并添加
        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_5), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(activity, cancelBtn, false);

        Button submitBtn = new Button(activity);
        submitBtn.setText(isEdit ? "保存" : "连接并添加");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        final String submitLabel = isEdit ? "保存" : "连接并添加";

        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 40)));
        LinearLayout.LayoutParams submitLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 120), UIUtils.dp(activity, 40));
        submitLp.setMargins(UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0, 0);
        btnBar.addView(submitBtn, submitLp);
        container.addView(btnBar, barLp);

        builder.setView(container);
        builder.setCancelable(true);
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        cancelBtn.setOnClickListener((v) -> dialog.dismiss());

        submitBtn.setOnClickListener((v) -> {
            String address = addressInput.getText().toString().trim();
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();

            if (address.isEmpty()) {
                showError(errorText, "地址不能为空");
                return;
            }
            if (username.isEmpty()) {
                showError(errorText, "账户不能为空");
                return;
            }
            if (password.isEmpty()) {
                showError(errorText, "密码不能为空");
                return;
            }
            DirectDeviceAddressNormalizer.Result normalized = DirectDeviceAddressNormalizer.normalize(address);
            if (!normalized.ok) {
                showError(errorText, normalized.error);
                return;
            }

            errorText.setVisibility(View.GONE);
            setSubmitting(activity, true, submitLabel, addressInput, usernameInput, passwordInput, cancelBtn, submitBtn);

            Callback callback = new Callback() {
                @Override
                public void onSuccess(String displayName) {
                    dialog.dismiss();
                }

                @Override
                public void onError(String message) {
                    setSubmitting(activity, false, submitLabel, addressInput, usernameInput, passwordInput, cancelBtn, submitBtn);
                    showError(errorText, message);
                }
            };
            if (isEdit) {
                host.updateDirectDevice(editing.getId(), normalized.url, username, password, callback);
            } else {
                host.submitDirectDevice(normalized.url, username, password, callback);
            }
        });
    }

    private static LinearLayout.LayoutParams fieldLayoutParams(int bottomGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, bottomGap);
        return lp;
    }

    private static void showError(TextView errorText, String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private static void setSubmitting(Activity activity, boolean submitting, String idleLabel,
                                      EditText address, EditText username, EditText password,
                                      Button cancelBtn, Button submitBtn) {
        address.setEnabled(!submitting);
        username.setEnabled(!submitting);
        password.setEnabled(!submitting);
        cancelBtn.setEnabled(!submitting);
        submitBtn.setEnabled(!submitting);
        submitBtn.setText(submitting ? "正在连接…" : idleLabel);
    }
}
