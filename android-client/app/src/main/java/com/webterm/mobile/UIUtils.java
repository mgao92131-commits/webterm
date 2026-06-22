package com.webterm.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * UI 工具：间距/dp 换算 + 通用背景与按钮样式构造器。
 *
 * 所有颜色与圆角都从 {@link DesignTokens} 读取，禁止在业务代码里硬编码 hex。
 */
public final class UIUtils {

    private UIUtils() {}

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 输入框标准高度（与 Web 端 h-10 对齐）。 */
    public static int inputHeight(Context context) {
        return dp(context, 40);
    }

    public static EditText createInput(Context context, String hint) {
        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(DesignTokens.TEXT_PRIMARY);
        editText.setHintTextColor(DesignTokens.TEXT_DISABLED);
        // 字体：输入框用 Geist Mono（与 Web 端 font-mono 13px 对齐）
        editText.setTypeface(DesignTokens.fontGeistMono(context));
        editText.setTextSize(DesignTokens.TEXT_MONO_SIZE);
        editText.setPadding(
            dp(context, DesignTokens.SPACE_3),
            dp(context, DesignTokens.SPACE_2),
            dp(context, DesignTokens.SPACE_3),
            dp(context, DesignTokens.SPACE_2)
        );

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(DesignTokens.BG_PRIMARY);
        gd.setStroke(dp(context, 1), DesignTokens.BORDER_PRIMARY);
        gd.setCornerRadius(dp(context, DesignTokens.RADIUS_SM));
        editText.setBackground(gd);
        return editText;
    }

    public static LinearLayout.LayoutParams matchWrap(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, inputHeight(context));
        lp.setMargins(0, 0, 0, dp(context, DesignTokens.SPACE_3));
        return lp;
    }

    public static void styleDialogButton(Context context, Button button, boolean primary) {
        button.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(
            dp(context, DesignTokens.SPACE_3),
            0,
            dp(context, DesignTokens.SPACE_3),
            0
        );
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, DesignTokens.RADIUS_SM));

        if (primary) {
            button.setTextColor(DesignTokens.BG_PRIMARY);
            drawable.setColor(DesignTokens.ACCENT);
            drawable.setStroke(dp(context, 1), DesignTokens.ACCENT);
        } else {
            button.setTextColor(DesignTokens.TEXT_PRIMARY);
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(dp(context, 1), DesignTokens.BORDER_PRIMARY);
        }
        button.setBackground(drawable);
    }

    public static void styleStepperButton(Context context, Button button) {
        button.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(android.view.Gravity.CENTER);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);

        button.setTextColor(DesignTokens.TEXT_PRIMARY);
        drawable.setColor(DesignTokens.BG_TERTIARY);
        drawable.setStroke(dp(context, 1), DesignTokens.BORDER_PRIMARY);

        button.setBackground(drawable);
    }

    /**
     * 卡片/面板底色：bg-secondary + border-primary + radius-md。
     * 与 Web 端 Card 组件对齐（rounded-md + 1px border + bg-secondary）。
     */
    public static GradientDrawable panelBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(DesignTokens.BG_SECONDARY);
        drawable.setStroke(dp(context, 1), DesignTokens.BORDER_PRIMARY);
        drawable.setCornerRadius(dp(context, DesignTokens.RADIUS_MD));
        return drawable;
    }

    /**
     * 对话框容器背景：bg-secondary + border-primary + radius-lg（极少使用）。
     * 对话框本身用更大的圆角以与卡片视觉区分。
     */
    public static GradientDrawable dialogBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(DesignTokens.BG_SECONDARY);
        drawable.setStroke(dp(context, 1), DesignTokens.BORDER_PRIMARY);
        drawable.setCornerRadius(dp(context, DesignTokens.RADIUS_LG));
        return drawable;
    }

    /**
     * 顶栏图标按钮背景：透明 + 圆角 + 边框（hover 时边框会高亮）。
     */
    public static GradientDrawable iconButtonBackground(Context context, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(context, radiusDp));
        // 无边框——与 Web 端一致，纯图标 + 水波纹反馈
        return drawable;
    }

    /**
     * 创建对话框标题行：[矢量图标] + [文字标题]。
     * 取代原来的 "⚙️ 终端显示设置" 模式，统一为 ImageView + TextView 横向布局。
     *
     * @param activity       Activity
     * @param iconRes        图标 drawable 资源
     * @param title          标题文字
     * @param titleColor     标题文字颜色
     * @param iconColor      图标颜色（用 DesignTokens 中的状态/文字色）
     * @param iconSizeDp     图标尺寸，默认 18dp（与 TEXT_DIALOG_TITLE 字号接近）
     * @return 横向 LinearLayout（含图标 + 文字）
     */
    public static android.widget.LinearLayout dialogTitleRow(
            android.app.Activity activity,
            int iconRes,
            String title,
            int titleColor,
            int iconColor,
            int iconSizeDp
    ) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(iconColor);
        android.widget.LinearLayout.LayoutParams iconLp = new android.widget.LinearLayout.LayoutParams(
                dp(activity, iconSizeDp), dp(activity, iconSizeDp));
        iconLp.setMargins(0, 0, dp(activity, DesignTokens.SPACE_2 + 2), 0);
        row.addView(icon, iconLp);

        android.widget.TextView text = new android.widget.TextView(activity);
        text.setText(title);
        text.setTextColor(titleColor);
        text.setTextSize(DesignTokens.TEXT_DIALOG_TITLE);
        text.setTypeface(DesignTokens.fontGeistSansSemibold(activity));
        row.addView(text);

        return row;
    }

    /** 同上，图标尺寸用默认 18dp。 */
    public static android.widget.LinearLayout dialogTitleRow(
            android.app.Activity activity,
            int iconRes,
            String title,
            int titleColor,
            int iconColor
    ) {
        return dialogTitleRow(activity, iconRes, title, titleColor, iconColor, 18);
    }

    /**
     * 为顶栏添加 1px 底部分割线（与 Web 端 border-b 等价）。
     * 用 GradientDrawable.setStroke 实现，Android 不支持 border-bottom 单边。
     */
    public static GradientDrawable topbarBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(DesignTokens.BG_SECONDARY);
        // setStroke 在 drawable 周围画一圈线，没有 border-bottom。
        // 这里我们返回纯色背景，分割线由调用方用另一个 View 加在下方。
        return drawable;
    }

    /**
     * 顶栏底部分割线（1px 高度的 View，背景色 = border-primary）。
     * 替代 XML 中的 android:dividerHeight，兼容纯代码构建。
     */
    public static android.view.View topbarDivider(Context context) {
        android.view.View divider = new android.view.View(context);
        divider.setBackgroundColor(DesignTokens.BORDER_PRIMARY);
        return divider;
    }

    /**
     * 统一顶栏构建器。三个页面（设备列表、会话列表、终端）共用同一套样式：
     *   - bg_secondary (#111113) 背景
     *   - 左右 padding = SPACE_2 (8dp)，上下无 padding（内容靠 gravity 居中）
     *   - 底部 1px border-primary 分割线
     *
     * @param context  Context
     * @param heightDp 顶栏高度（dp），设备/会话页 44dp，终端页 44dp
     * @return 统一顶栏 + 分割线容器（LinearLayout VERTICAL）
     */
    public static LinearLayout createTopbar(Context context, int heightDp) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topbar = new LinearLayout(context);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        topbar.setBackgroundColor(DesignTokens.BG_SECONDARY);
        topbar.setPadding(
            dp(context, DesignTokens.SPACE_2),
            0,
            dp(context, DesignTokens.SPACE_2),
            0
        );
        wrapper.addView(topbar, new LinearLayout.LayoutParams(-1, dp(context, heightDp)));

        // 底部分割线
        android.view.View divider = new android.view.View(context);
        divider.setBackgroundColor(DesignTokens.BORDER_PRIMARY);
        wrapper.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        // 把 topbar 的引用绑在 wrapper.tag 上，方便调用方取用
        wrapper.setTag(topbar);
        return wrapper;
    }

    /**
     * 从 createTopbar 返回的 wrapper 中取出真正的 topbar LinearLayout。
     */
    public static LinearLayout topbarFromWrapper(android.view.View wrapper) {
        return (LinearLayout) wrapper.getTag();
    }
}
