package com.webterm.mobile.ui.relay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.UIUtils;

import org.json.JSONArray;
import org.json.JSONObject;

public final class RelayDevicesScreenBuilder {

    public interface Host {
        Activity activity();
        void onFetchDevices(DevicesCallback callback);
        void onRegisterDevice(String deviceName, DeviceCreateCallback callback);
        void onDeleteDevice(String deviceId, SimpleCallback callback);
        void onFetchTrustedDevices(TrustedDevicesCallback callback);
        void onDeleteTrustedDevice(String trustedDeviceId, SimpleCallback callback);
        void onLogout();
        void onBackToHome();
    }

    public interface DevicesCallback {
        void onReady(JSONArray devices);
        void onError(String message);
    }

    public interface DeviceCreateCallback {
        void onReady(String deviceId, String deviceName, String agentSecret);
        void onError(String message);
    }

    public interface TrustedDevicesCallback {
        void onReady(JSONArray devices);
        void onError(String message);
    }

    public interface SimpleCallback {
        void onReady();
        void onError(String message);
    }

    public static final class RelayDevicesScreen {
        public final LinearLayout root;
        public final Runnable refresh;
        public final LinearLayout agentDeviceList;
        public final LinearLayout trustedDeviceList;
        public final TextView errorText;

        public RelayDevicesScreen(LinearLayout root, Runnable refresh,
                           LinearLayout agentDeviceList, LinearLayout trustedDeviceList,
                           TextView errorText) {
            this.root = root;
            this.refresh = refresh;
            this.agentDeviceList = agentDeviceList;
            this.trustedDeviceList = trustedDeviceList;
            this.errorText = errorText;
        }
    }

    public static RelayDevicesScreen build(Host host) {
        Activity activity = host.activity();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // === 顶栏 ===
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        android.widget.ImageButton backBtn = new android.widget.ImageButton(activity);
        backBtn.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        backBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        backBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        backBtn.setOnClickListener(v -> host.onBackToHome());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        TextView topTitle = new TextView(activity);
        topTitle.setText("设备管理");
        topTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        topTitle.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        topTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        topTitle.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);
        topbar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1));

        Button logoutBtn = new Button(activity);
        logoutBtn.setText("退出");
        UIUtils.styleDialogButton(activity, logoutBtn, false);
        logoutBtn.setTextColor(DesignTokens.DANGER);
        logoutBtn.setOnClickListener(v -> host.onLogout());
        topbar.addView(logoutBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 60), UIUtils.dp(activity, 34)));

        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        // === 内容区 ===
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4));

        // 错误提示
        TextView errorText = new TextView(activity);
        errorText.setTextColor(DesignTokens.DANGER);
        errorText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        content.addView(errorText, new LinearLayout.LayoutParams(-1, -2));

        // === Section A: PC Agent 设备 ===
        // 标题行
        LinearLayout agentHeaderRow = new LinearLayout(activity);
        agentHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        agentHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        agentHeaderRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, UIUtils.dp(activity, DesignTokens.SPACE_2));

        LinearLayout agentTitleArea = new LinearLayout(activity);
        agentTitleArea.setOrientation(LinearLayout.VERTICAL);
        TextView agentTitle = new TextView(activity);
        agentTitle.setText("PC Agent 设备");
        agentTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        agentTitle.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        agentTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        agentTitleArea.addView(agentTitle, new LinearLayout.LayoutParams(-2, -2));

        TextView agentDesc = new TextView(activity);
        agentDesc.setText("用于远程登录你电脑的终端 Agent");
        agentDesc.setTextColor(DesignTokens.TEXT_TERTIARY);
        agentDesc.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        agentTitleArea.addView(agentDesc, new LinearLayout.LayoutParams(-2, -2));

        agentHeaderRow.addView(agentTitleArea, new LinearLayout.LayoutParams(0, -2, 1));

        Button addDeviceBtn = new Button(activity);
        addDeviceBtn.setText("+ 添加设备");
        UIUtils.styleDialogButton(activity, addDeviceBtn, true);
        addDeviceBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        agentHeaderRow.addView(addDeviceBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 90), UIUtils.dp(activity, 34)));

        content.addView(agentHeaderRow, new LinearLayout.LayoutParams(-1, -2));

        // 添加设备表单（初始隐藏）
        LinearLayout addForm = buildAddDeviceForm(activity, host, errorText);
        addForm.setVisibility(View.GONE);
        content.addView(addForm, new LinearLayout.LayoutParams(-1, -2));

        // Secret 展示区（初始隐藏）
        LinearLayout secretReveal = buildSecretReveal(activity);
        secretReveal.setVisibility(View.GONE);
        content.addView(secretReveal, new LinearLayout.LayoutParams(-1, -2));

        // 设备列表容器
        LinearLayout agentDeviceList = new LinearLayout(activity);
        agentDeviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(agentDeviceList, new LinearLayout.LayoutParams(-1, -2));

        // === 分隔线 ===
        View divider = new View(activity);
        divider.setBackgroundColor(DesignTokens.BORDER_PRIMARY);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1));
        divLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, UIUtils.dp(activity, DesignTokens.SPACE_4));
        content.addView(divider, divLp);

        // === Section B: 信任设备 ===
        TextView trustedTitle = new TextView(activity);
        trustedTitle.setText("信任的浏览器/移动设备");
        trustedTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        trustedTitle.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        trustedTitle.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        trustedTitle.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_1));
        content.addView(trustedTitle, new LinearLayout.LayoutParams(-1, -2));

        TextView trustedDesc = new TextView(activity);
        trustedDesc.setText("已通过邮箱验证的设备。撤销信任后，该设备下次登录需重新输入验证码");
        trustedDesc.setTextColor(DesignTokens.TEXT_TERTIARY);
        trustedDesc.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        trustedDesc.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2));
        content.addView(trustedDesc, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout trustedDeviceList = new LinearLayout(activity);
        trustedDeviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(trustedDeviceList, new LinearLayout.LayoutParams(-1, -2));

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        // === 刷新逻辑 ===
        final Runnable[] refreshHolder = {null};
        refreshHolder[0] = () -> {
            errorText.setVisibility(View.GONE);
            host.onFetchDevices(new DevicesCallback() {
                @Override
                public void onReady(JSONArray devices) {
                    activity.runOnUiThread(() -> rebuildAgentDeviceList(activity, host, agentDeviceList, devices, errorText, refreshHolder[0]));
                }
                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        errorText.setText(message);
                        errorText.setVisibility(View.VISIBLE);
                    });
                }
            });
            host.onFetchTrustedDevices(new TrustedDevicesCallback() {
                @Override
                public void onReady(JSONArray devices) {
                    activity.runOnUiThread(() -> rebuildTrustedDeviceList(activity, host, trustedDeviceList, devices, errorText, refreshHolder[0]));
                }
                @Override
                public void onError(String message) {
                    // 信任设备获取失败不阻塞
                }
            });
        };
        Runnable refresh = refreshHolder[0];

        // 添加设备按钮
        addDeviceBtn.setOnClickListener(v -> {
            if (addForm.getVisibility() == View.VISIBLE) {
                addForm.setVisibility(View.GONE);
            } else {
                addForm.setVisibility(View.VISIBLE);
            }
        });

        // 表单中的取消按钮
        ((Button) addForm.findViewWithTag("cancelAddDevice")).setOnClickListener(v -> {
            addForm.setVisibility(View.GONE);
            EditText nameInput = (EditText) addForm.findViewWithTag("deviceNameInput");
            nameInput.setText("");
        });

        // 表单中的生成按钮
        ((Button) addForm.findViewWithTag("submitAddDevice")).setOnClickListener(v -> {
            EditText nameInput = (EditText) addForm.findViewWithTag("deviceNameInput");
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                errorText.setText("请输入设备名称");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            Button submitAdd = (Button) addForm.findViewWithTag("submitAddDevice");
            submitAdd.setEnabled(false);
            host.onRegisterDevice(name, new DeviceCreateCallback() {
                @Override
                public void onReady(String deviceId, String deviceName, String agentSecret) {
                    activity.runOnUiThread(() -> {
                        addForm.setVisibility(View.GONE);
                        nameInput.setText("");
                        submitAdd.setEnabled(true);
                        // 显示 secret
                        TextView secretText = (TextView) secretReveal.findViewWithTag("secretText");
                        secretText.setText(agentSecret);
                        secretReveal.setVisibility(View.VISIBLE);
                        refresh.run();
                    });
                }
                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        submitAdd.setEnabled(true);
                        errorText.setText(message);
                        errorText.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        // Secret 复制按钮
        secretReveal.findViewWithTag("copySecretBtn").setOnClickListener(v -> {
            TextView secretText = (TextView) secretReveal.findViewWithTag("secretText");
            String secret = secretText.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("agent_secret", secret));
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
        });

        // Secret 已保存按钮
        secretReveal.findViewWithTag("dismissSecretBtn").setOnClickListener(v -> {
            secretReveal.setVisibility(View.GONE);
        });

        return new RelayDevicesScreen(root, refresh, agentDeviceList, trustedDeviceList, errorText);
    }

    private static LinearLayout buildAddDeviceForm(Activity activity, Host host, TextView errorText) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        form.setBackground(UIUtils.panelBackground(activity));

        EditText nameInput = UIUtils.createInput(activity, "设备名称（如：MacBook Pro）");
        nameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameInput.setTag("deviceNameInput");
        form.addView(nameInput, UIUtils.matchWrap(activity));

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(activity, cancelBtn, false);
        cancelBtn.setTag("cancelAddDevice");
        btnRow.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 34)));

        View space = new View(activity);
        btnRow.addView(space, new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.SPACE_2), 1));

        Button submitBtn = new Button(activity);
        submitBtn.setText("生成 secret");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        submitBtn.setTag("submitAddDevice");
        btnRow.addView(submitBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 100), UIUtils.dp(activity, 34)));

        form.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
        return form;
    }

    private static LinearLayout buildSecretReveal(Activity activity) {
        LinearLayout reveal = new LinearLayout(activity);
        reveal.setOrientation(LinearLayout.VERTICAL);
        reveal.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        reveal.setBackground(UIUtils.panelBackground(activity));

        TextView warnText = new TextView(activity);
        warnText.setText("⚠ secret 仅显示一次，请立即复制保存");
        warnText.setTextColor(DesignTokens.DANGER);
        warnText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        reveal.addView(warnText, new LinearLayout.LayoutParams(-1, -2));

        TextView secretText = new TextView(activity);
        secretText.setTag("secretText");
        secretText.setTextColor(DesignTokens.TEXT_PRIMARY);
        secretText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        secretText.setTypeface(Typeface.MONOSPACE);
        secretText.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        secretText.setBackgroundColor(DesignTokens.BG_TERTIARY);
        reveal.addView(secretText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);

        Button copyBtn = new Button(activity);
        copyBtn.setText("复制");
        UIUtils.styleDialogButton(activity, copyBtn, true);
        copyBtn.setTag("copySecretBtn");
        btnRow.addView(copyBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 34)));

        View space = new View(activity);
        btnRow.addView(space, new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.SPACE_2), 1));

        Button dismissBtn = new Button(activity);
        dismissBtn.setText("我已保存");
        UIUtils.styleDialogButton(activity, dismissBtn, false);
        dismissBtn.setTag("dismissSecretBtn");
        btnRow.addView(dismissBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 80), UIUtils.dp(activity, 34)));

        reveal.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        TextView tipText = new TextView(activity);
        tipText.setText("将此 secret 配置到 PC Agent 的 RELAY_SECRET 环境变量后启动 Agent");
        tipText.setTextColor(DesignTokens.TEXT_TERTIARY);
        tipText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        tipText.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_1), 0, 0);
        reveal.addView(tipText, new LinearLayout.LayoutParams(-1, -2));

        return reveal;
    }
    private static void rebuildAgentDeviceList(Activity activity, Host host,
                                                LinearLayout container, JSONArray devices,
                                                TextView errorText, Runnable onRefresh) {
        container.removeAllViews();
        if (devices == null || devices.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无 PC Agent 设备");
            empty.setTextColor(DesignTokens.TEXT_TERTIARY);
            empty.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_6), 0, UIUtils.dp(activity, DesignTokens.SPACE_6));
            container.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String deviceId = d.optString("deviceId", "");
            String deviceName = d.optString("deviceName", "未知设备");
            boolean online = d.optBoolean("online", false);
            String lastSeenAt = d.optString("lastSeenAt", null);

            LinearLayout row = buildDeviceRow(activity, deviceName, online, lastSeenAt,
                () -> {
                    AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("删除设备")
                        .setMessage("确定要删除设备 \"" + deviceName + "\" 吗？该设备的 PC Agent 将无法再连接中转服务器。")
                        .setPositiveButton("删除", (dlg, which) -> {
                            host.onDeleteDevice(deviceId, new SimpleCallback() {
                                @Override
                                public void onReady() {
                                    activity.runOnUiThread(() -> {
                                        onRefresh.run();
                                    });
                                }
                                @Override
                                public void onError(String msg) {
                                    activity.runOnUiThread(() -> {
                                        errorText.setText(msg);
                                        errorText.setVisibility(View.VISIBLE);
                                    });
                                }
                            });
                        })
                        .setNegativeButton("取消", null)
                        .create();
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.show();
                });
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static LinearLayout buildDeviceRow(Activity activity, String name, boolean online,
                                                String lastSeenAt, Runnable onDelete) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        row.setBackground(UIUtils.panelBackground(activity));

        // 在线状态指示灯
        View dot = new View(activity);
        dot.setBackgroundColor(online ? DesignTokens.SUCCESS : DesignTokens.TEXT_TERTIARY);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
            UIUtils.dp(activity, 8), UIUtils.dp(activity, 8));
        dotLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        row.addView(dot, dotLp);

        // 设备信息
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView nameText = new TextView(activity);
        nameText.setText(name);
        nameText.setTextColor(DesignTokens.TEXT_PRIMARY);
        nameText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        nameText.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        info.addView(nameText, new LinearLayout.LayoutParams(-2, -2));

        TextView statusText = new TextView(activity);
        String statusStr = online ? "在线" : "离线";
        if (lastSeenAt != null && !lastSeenAt.isEmpty()) {
            statusStr += " · 最后在线: " + lastSeenAt;
        }
        statusText.setText(statusStr);
        statusText.setTextColor(DesignTokens.TEXT_TERTIARY);
        statusText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        info.addView(statusText, new LinearLayout.LayoutParams(-2, -2));

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        // 删除按钮
        Button deleteBtn = new Button(activity);
        deleteBtn.setText("删除");
        UIUtils.styleDialogButton(activity, deleteBtn, false);
        deleteBtn.setTextColor(DesignTokens.DANGER);
        deleteBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        deleteBtn.setOnClickListener(v -> onDelete.run());
        row.addView(deleteBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 50), UIUtils.dp(activity, 30)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_1));
        row.setLayoutParams(rowLp);
        return row;
    }

    private static void rebuildTrustedDeviceList(Activity activity, Host host,
                                                  LinearLayout container, JSONArray devices,
                                                  TextView errorText, Runnable onRefresh) {
        container.removeAllViews();
        if (devices == null || devices.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无信任设备");
            empty.setTextColor(DesignTokens.TEXT_TERTIARY);
            empty.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_4), 0, UIUtils.dp(activity, DesignTokens.SPACE_4));
            container.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("id", "");
            String deviceName = d.optString("deviceName", "未知设备");
            String lastSeenAt = d.optString("lastSeenAt", null);
            String createdAt = d.optString("createdAt", null);

            LinearLayout row = buildTrustedDeviceRow(activity, deviceName, lastSeenAt, createdAt,
                () -> {
                    AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("撤销信任")
                        .setMessage("确定要撤销对 \"" + deviceName + "\" 的信任吗？")
                        .setPositiveButton("撤销", (dlg, which) -> {
                            host.onDeleteTrustedDevice(id, new SimpleCallback() {
                                @Override
                                public void onReady() {
                                    activity.runOnUiThread(() -> {
                                        onRefresh.run();
                                    });
                                }
                                @Override
                                public void onError(String msg) {
                                    activity.runOnUiThread(() -> {
                                        errorText.setText(msg);
                                        errorText.setVisibility(View.VISIBLE);
                                    });
                                }
                            });
                        })
                        .setNegativeButton("取消", null)
                        .create();
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.show();
                });
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static LinearLayout buildTrustedDeviceRow(Activity activity, String name,
                                                       String lastSeenAt, String createdAt,
                                                       Runnable onRevoke) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        row.setBackground(UIUtils.panelBackground(activity));

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView nameText = new TextView(activity);
        nameText.setText(name);
        nameText.setTextColor(DesignTokens.TEXT_PRIMARY);
        nameText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        info.addView(nameText, new LinearLayout.LayoutParams(-2, -2));

        StringBuilder detail = new StringBuilder();
        if (lastSeenAt != null && !lastSeenAt.isEmpty()) {
            detail.append("最后活跃: ").append(lastSeenAt);
        }
        if (createdAt != null && !createdAt.isEmpty()) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append("添加于: ").append(createdAt);
        }
        TextView detailText = new TextView(activity);
        detailText.setText(detail.toString());
        detailText.setTextColor(DesignTokens.TEXT_TERTIARY);
        detailText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        info.addView(detailText, new LinearLayout.LayoutParams(-2, -2));

        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        Button revokeBtn = new Button(activity);
        revokeBtn.setText("撤销信任");
        UIUtils.styleDialogButton(activity, revokeBtn, false);
        revokeBtn.setTextColor(DesignTokens.DANGER);
        revokeBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        revokeBtn.setOnClickListener(v -> onRevoke.run());
        row.addView(revokeBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 70), UIUtils.dp(activity, 30)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_1));
        row.setLayoutParams(rowLp);
        return row;
    }
}
