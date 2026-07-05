package com.webterm.feature.relay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.R;
import com.webterm.ui.common.UIUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

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

    /** 缓存当前页面用到的字体，避免列表行反复从 assets 读取。 */
    private static final class Fonts {
        final Typeface sans;
        final Typeface sansSemibold;
        final Typeface mono;

        Fonts(Activity activity) {
            sans = DesignTokens.fontGeistSans(activity);
            sansSemibold = DesignTokens.fontGeistSansSemibold(activity);
            mono = DesignTokens.fontGeistMono(activity);
        }
    }

    public static RelayDevicesScreen build(Host host) {
        Activity activity = host.activity();
        Fonts fonts = new Fonts(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // === 顶栏 ===
        View topbarWrapper = UIUtils.createTopbar(activity, DesignTokens.TOPBAR_HEIGHT_HOME);
        LinearLayout topbar = UIUtils.topbarFromWrapper(topbarWrapper);

        ImageButton backBtn = new ImageButton(activity);
        backBtn.setImageResource(R.drawable.ic_arrow_back);
        backBtn.setColorFilter(DesignTokens.TEXT_PRIMARY);
        backBtn.setBackground(UIUtils.iconButtonBackground(activity, 18));
        backBtn.setOnClickListener(v -> host.onBackToHome());
        topbar.addView(backBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 40), UIUtils.dp(activity, 40)));

        TextView topTitle = new TextView(activity);
        topTitle.setText("设备管理");
        topTitle.setTextColor(DesignTokens.TEXT_PRIMARY);
        topTitle.setTextSize(DesignTokens.TEXT_BRAND_SIZE);
        topTitle.setTypeface(fonts.sansSemibold);
        topTitle.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0, 0);
        topbar.addView(topTitle, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(topbarWrapper, new LinearLayout.LayoutParams(-1, -2));

        // === 内容区 ===
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_6));

        // 错误提示
        TextView errorText = new TextView(activity);
        errorText.setTextColor(DesignTokens.DANGER);
        errorText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(-1, -2);
        errorLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3));
        content.addView(errorText, errorLp);

        // === Section A: PC Agent 设备 ===
        LinearLayout agentHeader = buildSectionHeader(activity, fonts,
            "PC Agent 设备",
            "用于远程登录你电脑的终端 Agent");
        Button addDeviceBtn = new Button(activity);
        addDeviceBtn.setText("+ 添加设备");
        UIUtils.styleDialogButton(activity, addDeviceBtn, true);
        addDeviceBtn.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        LinearLayout headerRow = (LinearLayout) agentHeader.getChildAt(0);
        headerRow.addView(addDeviceBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 92), UIUtils.dp(activity, 34)));
        content.addView(agentHeader, new LinearLayout.LayoutParams(-1, -2));

        // 添加设备表单（初始隐藏）
        LinearLayout addForm = buildAddDeviceForm(activity, fonts);
        addForm.setVisibility(View.GONE);
        content.addView(addForm, new LinearLayout.LayoutParams(-1, -2));

        // Secret 展示区（初始隐藏）
        LinearLayout secretReveal = buildSecretReveal(activity, fonts);
        secretReveal.setVisibility(View.GONE);
        content.addView(secretReveal, new LinearLayout.LayoutParams(-1, -2));

        // 设备列表容器
        LinearLayout agentDeviceList = new LinearLayout(activity);
        agentDeviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(agentDeviceList, new LinearLayout.LayoutParams(-1, -2));

        // === 区块分割线 ===
        View sectionDivider = new View(activity);
        sectionDivider.setBackgroundColor(DesignTokens.BORDER_PRIMARY);
        LinearLayout.LayoutParams sectionDivLp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1));
        sectionDivLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_5), 0, UIUtils.dp(activity, DesignTokens.SPACE_5));
        content.addView(sectionDivider, sectionDivLp);

        // === Section B: 信任设备 ===
        LinearLayout trustedHeader = buildSectionHeader(activity, fonts,
            "信任的浏览器/移动设备",
            "已通过邮箱验证的设备。撤销信任后，该设备下次登录需重新输入验证码");
        content.addView(trustedHeader, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout trustedDeviceList = new LinearLayout(activity);
        trustedDeviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(trustedDeviceList, new LinearLayout.LayoutParams(-1, -2));

        // === 底部：退出登录（次要入口）===
        Button logoutBtn = new Button(activity);
        logoutBtn.setText("退出登录");
        styleGhostButton(activity, logoutBtn);
        logoutBtn.setOnClickListener(v -> host.onLogout());
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 40));
        logoutLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_6), 0, 0);
        content.addView(logoutBtn, logoutLp);

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        // === 刷新逻辑 ===
        final Runnable[] refreshHolder = {null};
        refreshHolder[0] = () -> {
            errorText.setVisibility(View.GONE);
            host.onFetchDevices(new DevicesCallback() {
                @Override
                public void onReady(JSONArray devices) {
                    activity.runOnUiThread(() -> rebuildAgentDeviceList(activity, host, fonts, agentDeviceList, devices, errorText, refreshHolder[0]));
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
                    activity.runOnUiThread(() -> rebuildTrustedDeviceList(activity, host, fonts, trustedDeviceList, devices, errorText, refreshHolder[0]));
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
                secretReveal.setVisibility(View.GONE);
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

    // -------------------------------------------------------------------------
    // 时间格式化
    // -------------------------------------------------------------------------
    private static String formatTime(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        String normalized = iso.endsWith("Z") ? iso : iso + "Z";

        // 尝试带毫秒
        String formatted = tryParseFormat(normalized, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        if (formatted == null) {
            // 尝试不带毫秒
            formatted = tryParseFormat(normalized, "yyyy-MM-dd'T'HH:mm:ss'Z'");
        }
        return formatted != null ? formatted : iso;
    }

    private static String tryParseFormat(String normalized, String pattern) {
        try {
            SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = parser.parse(normalized);
            if (date == null) return null;
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            return formatter.format(date);
        } catch (ParseException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // 区块标题
    // -------------------------------------------------------------------------
    private static LinearLayout buildSectionHeader(Activity activity, Fonts fonts, String title, String desc) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3));

        LinearLayout titleArea = new LinearLayout(activity);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView titleText = new TextView(activity);
        titleText.setText(title);
        titleText.setTextColor(DesignTokens.TEXT_PRIMARY);
        titleText.setTextSize(DesignTokens.TEXT_HEADING_SIZE);
        titleText.setTypeface(fonts.sansSemibold);
        titleArea.addView(titleText, new LinearLayout.LayoutParams(-1, -2));

        TextView descText = new TextView(activity);
        descText.setText(desc);
        descText.setTextColor(DesignTokens.TEXT_TERTIARY);
        descText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_1), 0, 0);
        titleArea.addView(descText, descLp);

        row.addView(titleArea);
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(activity);
        divider.setBackgroundColor(DesignTokens.BORDER_PRIMARY);
        wrapper.addView(divider, new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1)));

        return wrapper;
    }

    // -------------------------------------------------------------------------
    // 添加设备表单
    // -------------------------------------------------------------------------
    private static LinearLayout buildAddDeviceForm(Activity activity, Fonts fonts) {
        // 外层：左侧 accent 竖线 + 内容区
        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackgroundColor(DesignTokens.BG_SECONDARY);
        LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(-1, -2);
        outerLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, UIUtils.dp(activity, DesignTokens.SPACE_2));
        outer.setLayoutParams(outerLp);

        View accentLine = new View(activity);
        accentLine.setBackgroundColor(DesignTokens.ACCENT);
        outer.addView(accentLine, new LinearLayout.LayoutParams(UIUtils.dp(activity, 2), -1));

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        form.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        EditText nameInput = UIUtils.createInput(activity, "设备名称（如：MacBook Pro）");
        nameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameInput.setTag("deviceNameInput");
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, UIUtils.inputHeight(activity));
        inputLp.setMargins(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2));
        form.addView(nameInput, inputLp);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button cancelBtn = new Button(activity);
        cancelBtn.setText("取消");
        styleGhostButton(activity, cancelBtn);
        cancelBtn.setTag("cancelAddDevice");
        btnRow.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 64), UIUtils.dp(activity, 34)));

        View space = new View(activity);
        btnRow.addView(space, new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.SPACE_2), 1));

        Button submitBtn = new Button(activity);
        submitBtn.setText("生成 secret");
        UIUtils.styleDialogButton(activity, submitBtn, true);
        submitBtn.setTag("submitAddDevice");
        btnRow.addView(submitBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 104), UIUtils.dp(activity, 34)));

        form.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
        outer.addView(form);
        return outer;
    }

    // -------------------------------------------------------------------------
    // Secret 展示区
    // -------------------------------------------------------------------------
    private static LinearLayout buildSecretReveal(Activity activity, Fonts fonts) {
        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackgroundColor(DesignTokens.dangerBg());
        LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(-1, -2);
        outerLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, UIUtils.dp(activity, DesignTokens.SPACE_2));
        outer.setLayoutParams(outerLp);

        View dangerLine = new View(activity);
        dangerLine.setBackgroundColor(DesignTokens.DANGER);
        outer.addView(dangerLine, new LinearLayout.LayoutParams(UIUtils.dp(activity, 2), -1));

        LinearLayout reveal = new LinearLayout(activity);
        reveal.setOrientation(LinearLayout.VERTICAL);
        reveal.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        reveal.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView warnText = new TextView(activity);
        warnText.setText("secret 仅显示一次，请立即复制保存");
        warnText.setTextColor(DesignTokens.DANGER);
        warnText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        warnText.setTypeface(fonts.sansSemibold);
        reveal.addView(warnText, new LinearLayout.LayoutParams(-1, -2));

        TextView secretText = new TextView(activity);
        secretText.setTag("secretText");
        secretText.setTextColor(DesignTokens.TEXT_PRIMARY);
        secretText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        secretText.setTypeface(fonts.mono);
        secretText.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, DesignTokens.SPACE_2));
        GradientDrawable secretBg = new GradientDrawable();
        secretBg.setShape(GradientDrawable.RECTANGLE);
        secretBg.setColor(DesignTokens.BG_PRIMARY);
        secretBg.setStroke(UIUtils.dp(activity, 1), DesignTokens.BORDER_PRIMARY);
        secretBg.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));
        secretText.setBackground(secretBg);
        LinearLayout.LayoutParams secretLp = new LinearLayout.LayoutParams(-1, -2);
        secretLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);
        reveal.addView(secretText, secretLp);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);

        Button copyBtn = new Button(activity);
        copyBtn.setText("复制");
        UIUtils.styleDialogButton(activity, copyBtn, true);
        copyBtn.setTag("copySecretBtn");
        btnRow.addView(copyBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 72), UIUtils.dp(activity, 34)));

        View space = new View(activity);
        btnRow.addView(space, new LinearLayout.LayoutParams(UIUtils.dp(activity, DesignTokens.SPACE_2), 1));

        Button dismissBtn = new Button(activity);
        dismissBtn.setText("我已保存");
        styleGhostButton(activity, dismissBtn);
        dismissBtn.setTag("dismissSecretBtn");
        btnRow.addView(dismissBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 84), UIUtils.dp(activity, 34)));

        reveal.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        TextView tipText = new TextView(activity);
        tipText.setText("将此 secret 配置到 PC Agent 的 RELAY_SECRET 环境变量后启动 Agent");
        tipText.setTextColor(DesignTokens.TEXT_TERTIARY);
        tipText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0, 0);
        reveal.addView(tipText, tipLp);

        outer.addView(reveal);
        return outer;
    }

    // -------------------------------------------------------------------------
    // PC Agent 设备列表
    // -------------------------------------------------------------------------
    private static void rebuildAgentDeviceList(Activity activity, Host host, Fonts fonts,
                                                LinearLayout container, JSONArray devices,
                                                TextView errorText, Runnable onRefresh) {
        container.removeAllViews();
        if (devices == null || devices.length() == 0) {
            container.addView(buildEmptyState(activity, fonts, "暂无 PC Agent 设备", R.drawable.ic_inbox),
                new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String deviceId = d.optString("deviceId", "");
            String deviceName = d.optString("deviceName", "未知设备");
            boolean online = d.optBoolean("online", false);
            String lastSeenAt = d.optString("lastSeenAt", null);

            LinearLayout row = buildDeviceRow(activity, fonts, deviceName, online, lastSeenAt,
                () -> {
                    AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("删除设备")
                        .setMessage("确定要删除设备 \"" + deviceName + "\" 吗？该设备的 PC Agent 将无法再连接中转服务器。")
                        .setPositiveButton("删除", (dlg, which) -> {
                            host.onDeleteDevice(deviceId, new SimpleCallback() {
                                @Override
                                public void onReady() {
                                    activity.runOnUiThread(onRefresh::run);
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
            if (i < devices.length() - 1) {
                container.addView(createRowDivider(activity), new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1)));
            }
        }
    }

    private static LinearLayout buildDeviceRow(Activity activity, Fonts fonts, String name, boolean online,
                                                String lastSeenAt, Runnable onDelete) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        row.setBackgroundColor(DesignTokens.BG_PRIMARY);

        // 设备图标
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_computer);
        icon.setColorFilter(DesignTokens.TEXT_SECONDARY);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 20), UIUtils.dp(activity, 20));
        iconLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0);
        row.addView(icon, iconLp);

        // 设备信息
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView nameText = new TextView(activity);
        nameText.setText(name);
        nameText.setTextColor(DesignTokens.TEXT_PRIMARY);
        nameText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        nameText.setTypeface(fonts.sans);
        info.addView(nameText, new LinearLayout.LayoutParams(-1, -2));

        String statusStr = (online ? "在线" : "离线");
        String formatted = formatTime(lastSeenAt);
        if (formatted != null) {
            statusStr += " · 最后在线 " + formatted;
        }
        TextView statusText = new TextView(activity);
        statusText.setText(statusStr);
        statusText.setTextColor(DesignTokens.TEXT_TERTIARY);
        statusText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        statusText.setTypeface(fonts.mono);
        info.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        row.addView(info);

        // 状态 badge
        TextView badge = createStatusBadge(activity, fonts, online);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_2), 0);
        row.addView(badge, badgeLp);

        // 删除按钮
        ImageButton deleteBtn = createIconButton(activity, R.drawable.ic_delete, DesignTokens.TEXT_TERTIARY);
        deleteBtn.setOnClickListener(v -> onDelete.run());
        row.addView(deleteBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 36), UIUtils.dp(activity, 36)));

        return row;
    }

    // -------------------------------------------------------------------------
    // 信任设备列表
    // -------------------------------------------------------------------------
    private static void rebuildTrustedDeviceList(Activity activity, Host host, Fonts fonts,
                                                  LinearLayout container, JSONArray devices,
                                                  TextView errorText, Runnable onRefresh) {
        container.removeAllViews();
        if (devices == null || devices.length() == 0) {
            container.addView(buildEmptyState(activity, fonts, "暂无信任设备", R.drawable.ic_inbox),
                new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("id", "");
            String deviceName = d.optString("deviceName", "未知设备");
            String lastSeenAt = d.optString("lastSeenAt", null);
            String createdAt = d.optString("createdAt", null);

            LinearLayout row = buildTrustedDeviceRow(activity, fonts, deviceName, lastSeenAt, createdAt,
                () -> {
                    AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("撤销信任")
                        .setMessage("确定要撤销对 \"" + deviceName + "\" 的信任吗？")
                        .setPositiveButton("撤销", (dlg, which) -> {
                            host.onDeleteTrustedDevice(id, new SimpleCallback() {
                                @Override
                                public void onReady() {
                                    activity.runOnUiThread(onRefresh::run);
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
            if (i < devices.length() - 1) {
                container.addView(createRowDivider(activity), new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1)));
            }
        }
    }

    private static LinearLayout buildTrustedDeviceRow(Activity activity, Fonts fonts, String name,
                                                       String lastSeenAt, String createdAt,
                                                       Runnable onRevoke) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_3),
            UIUtils.dp(activity, DesignTokens.SPACE_4),
            UIUtils.dp(activity, DesignTokens.SPACE_3));
        row.setBackgroundColor(DesignTokens.BG_PRIMARY);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_globe);
        icon.setColorFilter(DesignTokens.TEXT_SECONDARY);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(UIUtils.dp(activity, 20), UIUtils.dp(activity, 20));
        iconLp.setMargins(0, 0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0);
        row.addView(icon, iconLp);

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView nameText = new TextView(activity);
        nameText.setText(name);
        nameText.setTextColor(DesignTokens.TEXT_PRIMARY);
        nameText.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        nameText.setTypeface(fonts.sans);
        info.addView(nameText, new LinearLayout.LayoutParams(-1, -2));

        String formattedLastSeen = formatTime(lastSeenAt);
        if (formattedLastSeen != null) {
            TextView lastSeenText = new TextView(activity);
            lastSeenText.setText("最后活跃 " + formattedLastSeen);
            lastSeenText.setTextColor(DesignTokens.TEXT_TERTIARY);
            lastSeenText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
            lastSeenText.setTypeface(fonts.mono);
            info.addView(lastSeenText, new LinearLayout.LayoutParams(-1, -2));
        }

        String formattedCreated = formatTime(createdAt);
        if (formattedCreated != null) {
            TextView createdText = new TextView(activity);
            createdText.setText("添加于 " + formattedCreated);
            createdText.setTextColor(DesignTokens.TEXT_TERTIARY);
            createdText.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
            createdText.setTypeface(fonts.mono);
            info.addView(createdText, new LinearLayout.LayoutParams(-1, -2));
        }

        row.addView(info);

        ImageButton revokeBtn = createIconButton(activity, R.drawable.ic_logout, DesignTokens.TEXT_TERTIARY);
        revokeBtn.setOnClickListener(v -> onRevoke.run());
        row.addView(revokeBtn, new LinearLayout.LayoutParams(UIUtils.dp(activity, 36), UIUtils.dp(activity, 36)));

        return row;
    }

    // -------------------------------------------------------------------------
    // 通用辅助方法
    // -------------------------------------------------------------------------
    private static View createRowDivider(Activity activity) {
        View divider = new View(activity);
        divider.setBackgroundColor(DesignTokens.BORDER_SECONDARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, UIUtils.dp(activity, 1));
        lp.setMargins(UIUtils.dp(activity, DesignTokens.SPACE_4), 0, 0, 0);
        divider.setLayoutParams(lp);
        return divider;
    }

    private static TextView createStatusBadge(Activity activity, Fonts fonts, boolean online) {
        TextView badge = new TextView(activity);
        badge.setText(online ? "在线" : "离线");
        badge.setTextSize(DesignTokens.TEXT_CAPTION_SIZE);
        badge.setTypeface(fonts.sansSemibold);
        badge.setPadding(
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, 2),
            UIUtils.dp(activity, DesignTokens.SPACE_2),
            UIUtils.dp(activity, 2));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));
        if (online) {
            badge.setTextColor(DesignTokens.SUCCESS);
            bg.setColor(DesignTokens.successBg());
        } else {
            badge.setTextColor(DesignTokens.TEXT_TERTIARY);
            bg.setColor(DesignTokens.BG_TERTIARY);
        }
        badge.setBackground(bg);
        return badge;
    }

    private static ImageButton createIconButton(Activity activity, int iconRes, int defaultColor) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(iconRes);
        btn.setColorFilter(defaultColor);
        btn.setBackground(UIUtils.iconButtonBackground(activity, DesignTokens.RADIUS_SM));
        return btn;
    }

    private static LinearLayout buildEmptyState(Activity activity, Fonts fonts, String message, int iconRes) {
        LinearLayout empty = new LinearLayout(activity);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, UIUtils.dp(activity, DesignTokens.SPACE_6), 0, UIUtils.dp(activity, DesignTokens.SPACE_6));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(DesignTokens.TEXT_TERTIARY);
        empty.addView(icon, new LinearLayout.LayoutParams(UIUtils.dp(activity, 36), UIUtils.dp(activity, 36)));

        TextView text = new TextView(activity);
        text.setText(message);
        text.setTextColor(DesignTokens.TEXT_SECONDARY);
        text.setTextSize(DesignTokens.TEXT_BODY_SIZE);
        text.setTypeface(fonts.sans);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(-2, -2);
        textLp.setMargins(0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0, 0);
        empty.addView(text, textLp);

        return empty;
    }

    private static void styleGhostButton(Activity activity, Button button) {
        button.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(UIUtils.dp(activity, DesignTokens.SPACE_3), 0, UIUtils.dp(activity, DesignTokens.SPACE_3), 0);
        button.setTextColor(DesignTokens.TEXT_SECONDARY);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(DesignTokens.BG_PRIMARY);
        drawable.setStroke(UIUtils.dp(activity, 1), DesignTokens.BORDER_PRIMARY);
        drawable.setCornerRadius(UIUtils.dp(activity, DesignTokens.RADIUS_SM));
        button.setBackground(drawable);
    }
}
