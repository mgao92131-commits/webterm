package com.webterm.mobile.ui.common;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;

/**
 * WebTerm 设计系统 v2 (Android 端口)。
 *
 * 与 docs/design-system.md 一一对应，Web 端与 Android 端从此处共享同一组语义。
 * 所有 UI 颜色、间距、圆角、字号、字重都必须通过本类访问，禁止在业务代码里硬编码。
 *
 * 美学方向：精致暗色 / Swiss 现代主义。
 *   - 单一强调色 #10B981 翠绿
 *   - 圆角 4/6/8（克制，不用投影和渐变）
 *   - 字体 Geist Sans + Geist Mono（assets/fonts/）
 */
public final class DesignTokens {

    private DesignTokens() {}

    // ---------------------------------------------------------------------
    // 颜色（与 docs/design-system.md 的 CSS 变量一一对应）
    // ---------------------------------------------------------------------

    /** 页面底色。最深背景，等价于 --bg-primary。 */
    public static final int BG_PRIMARY       = 0xFF0A0A0B;
    /** 面板/卡片底色，等价于 --bg-secondary。 */
    public static final int BG_SECONDARY     = 0xFF111113;
    /** 悬停态/hover 背景，等价于 --bg-tertiary。 */
    public static final int BG_TERTIARY      = 0xFF18181B;

    /** 主要分割线/卡片边框，等价于 --border-primary。 */
    public static final int BORDER_PRIMARY   = 0xFF27272A;
    /** 次要分割线，等价于 --border-secondary。 */
    public static final int BORDER_SECONDARY = 0xFF1F1F23;
    /** 悬停态边框，等价于 --border-hover。 */
    public static final int BORDER_HOVER     = 0xFF3F3F46;

    /** 主文字，等价于 --text-primary。 */
    public static final int TEXT_PRIMARY     = 0xFFFAFAFA;
    /** 次要文字/标签，等价于 --text-secondary。 */
    public static final int TEXT_SECONDARY   = 0xFFA1A1AA;
    /** 辅助信息/placeholder，等价于 --text-tertiary。 */
    public static final int TEXT_TERTIARY    = 0xFF71717A;
    /** 禁用态文字，等价于 --text-disabled。 */
    public static final int TEXT_DISABLED    = 0xFF52525B;

    /** 主强调色（翠绿），等价于 --accent。 */
    public static final int ACCENT           = 0xFF10B981;
    /** 强调色悬停态，等价于 --accent-hover。 */
    public static final int ACCENT_HOVER     = 0xFF34D399;
    /** 强调色上的文字色（在浅绿背景上使用），等价于 --accent-text。 */
    public static final int ACCENT_TEXT      = 0xFF6EE7B7;

    /** 在线/成功状态色。 */
    public static final int SUCCESS          = 0xFF10B981;
    /** 警告/连接中状态色。 */
    public static final int WARNING          = 0xFFF59E0B;
    /** 离线/错误/危险操作色。 */
    public static final int DANGER           = 0xFFEF4444;
    /** 信息色（保留备用，Web 端当前未大量使用）。 */
    public static final int INFO             = 0xFF3B82F6;

    /** 终端画布背景。xterm 内部会覆盖。 */
    public static final int TERMINAL_BG      = 0xFF000000;
    /** 终端默认前景文字。 */
    public static final int TERMINAL_FG      = 0xFFE5E7EB;

    /** 浮层/蒙层（80% 黑），用于断线遮罩。 */
    public static final int OVERLAY          = 0xCC000000; // argb(204, 0, 0, 0)

    // ---------------------------------------------------------------------
    // 强调色透明背景（用于 Badge 底色、hover 高亮等场景）
    // ---------------------------------------------------------------------

    /** 强调色 10% 透明背景，等价于 --accent-muted。 */
    public static int accentBg() {
        return withAlpha(ACCENT, 0x1A); // 10%
    }
    /** 强调色 30% 透明背景（徽章/标签）。 */
    public static int accentBgStrong() {
        return withAlpha(ACCENT, 0x4D); // 30%
    }
    /** 危险色 10% 透明背景。 */
    public static int dangerBg() {
        return withAlpha(DANGER, 0x1A);
    }
    /** 警告色 10% 透明背景。 */
    public static int warningBg() {
        return withAlpha(WARNING, 0x1A);
    }
    /** 成功色 10% 透明背景。 */
    public static int successBg() {
        return withAlpha(SUCCESS, 0x1A);
    }

    // ---------------------------------------------------------------------
    // 间距（dp）
    // ---------------------------------------------------------------------

    public static final int SPACE_1 = 4;   // 紧凑间距（图标与文字）
    public static final int SPACE_2 = 8;   // 元素内间距
    public static final int SPACE_3 = 12;  // 组内间距
    public static final int SPACE_4 = 16;  // 区块内间距
    public static final int SPACE_5 = 24;  // 区块间间距
    public static final int SPACE_6 = 32;  // 页面级间距
    public static final int SPACE_8 = 48;  // 大区块间距

    // ---------------------------------------------------------------------
    // 圆角（dp）
    // ---------------------------------------------------------------------

    /** 小型按钮/徽章/输入框。 */
    public static final int RADIUS_SM = 4;
    /** 卡片/面板。 */
    public static final int RADIUS_MD = 6;
    /** 大型面板（极少使用，如对话框容器）。 */
    public static final int RADIUS_LG = 8;

    // ---------------------------------------------------------------------
    // 字号（sp，Android 端用 sp 自动跟随系统字号缩放）
    // ---------------------------------------------------------------------

    public static final float TEXT_BRAND_SIZE   = 16f;  // 品牌名
    public static final float TEXT_HEADING_SIZE = 14f;  // 区块标题
    public static final float TEXT_BODY_SIZE    = 14f;  // 正文
    public static final float TEXT_LABEL_SIZE   = 12f;  // 标签/徽章
    public static final float TEXT_CAPTION_SIZE = 11f;  // 辅助信息
    public static final float TEXT_MONO_SIZE    = 13f;  // 代码/终端默认
    public static final float TEXT_DIALOG_TITLE = 16f;  // 对话框标题（保持稍大以匹配 Material 习惯）

    // ---------------------------------------------------------------------
    // 字重（Typeface 常量映射）
    // ---------------------------------------------------------------------

    public static final int WEIGHT_REGULAR = 400;
    public static final int WEIGHT_MEDIUM  = 500;
    public static final int WEIGHT_SEMIBOLD = 600;
    public static final int WEIGHT_BOLD    = 700;

    /** 标题字重（heading/brand/button）。 */
    public static Typeface fontBold() { return Typeface.DEFAULT_BOLD; }
    /** 正文字重。 */
    public static Typeface fontRegular() { return Typeface.DEFAULT; }
    /** 等宽字重（终端、recent input、ID）。 */
    public static Typeface fontMono() { return Typeface.MONOSPACE; }
    /** 等宽粗体（极少使用）。 */
    public static Typeface fontMonoBold() { return Typeface.MONOSPACE; }

    // ---------------------------------------------------------------------
    // 字体加载（Geist Sans / Geist Mono）
    // ---------------------------------------------------------------------
    //
    // 字体文件位于 assets/fonts/，由 Geist 1.7.2 静态切割而成：
    //   geist_sans.ttf          - Geist Sans Regular (400)
    //   geist_sans_semibold.ttf - Geist Sans SemiBold (600)
    //   geist_sans_bold.ttf     - Geist Sans Bold (700)
    //   geist_mono.ttf          - Geist Mono Regular (400)
    //   geist_mono_semibold.ttf - Geist Mono SemiBold (600)
    //   geist_mono_bold.ttf     - Geist Mono Bold (700)
    //
    // 优先使用具体字重文件；Typeface.create(weight) 在国产 ROM 上不靠谱。

    /** 加载 Geist Sans Regular（UI 默认字体）。 */
    public static Typeface fontGeistSans(Context context) {
        return Typeface.createFromAsset(context.getAssets(), "fonts/geist_sans.ttf");
    }

    /** 加载 Geist Sans SemiBold（heading/品牌名）。 */
    public static Typeface fontGeistSansSemibold(Context context) {
        return Typeface.createFromAsset(context.getAssets(), "fonts/geist_sans_semibold.ttf");
    }

    /** 加载 Geist Sans Bold（极少使用，命令式按钮文字）。 */
    public static Typeface fontGeistSansBold(Context context) {
        return Typeface.createFromAsset(context.getAssets(), "fonts/geist_sans_bold.ttf");
    }

    /** 加载 Geist Mono Regular（终端默认/ID/路径）。 */
    public static Typeface fontGeistMono(Context context) {
        return Typeface.createFromAsset(context.getAssets(), "fonts/geist_mono.ttf");
    }

    /** 加载 Geist Mono SemiBold（最近输入框、强调等宽文字）。 */
    public static Typeface fontGeistMonoSemibold(Context context) {
        return Typeface.createFromAsset(context.getAssets(), "fonts/geist_mono_semibold.ttf");
    }

    /** 加载 Geist Mono Bold（极少使用）。 */
    public static Typeface fontGeistMonoBold(Context context) {
        return Typeface.createFromAsset(context.getAssets(), "fonts/geist_mono_bold.ttf");
    }

    /**
     * 根据字重加载 Geist Sans。400→Regular, 500→Regular, 600→SemiBold, 700→Bold。
     * 避免依赖 Typeface.create(weight)，国产 ROM 上经常不生效。
     */
    public static Typeface fontGeistSans(Context context, int weight) {
        if (weight >= 700) return fontGeistSansBold(context);
        if (weight >= 600) return fontGeistSansSemibold(context);
        return fontGeistSans(context);
    }

    /** 同上，但用于 Geist Mono。 */
    public static Typeface fontGeistMono(Context context, int weight) {
        if (weight >= 700) return fontGeistMonoBold(context);
        if (weight >= 600) return fontGeistMonoSemibold(context);
        return fontGeistMono(context);
    }

    // ---------------------------------------------------------------------
    // 组件尺寸（dp）
    // ---------------------------------------------------------------------

    /** 设备页顶栏高度（对齐 Web 端 44px）。 */
    public static final int TOPBAR_HEIGHT_HOME    = 44;
    /** 终端页顶栏高度（统一 44dp，与设备/会话页一致）。 */
    public static final int TOPBAR_HEIGHT_TERMINAL = 44;
    /** 顶栏图标按钮尺寸。 */
    public static final int TOPBAR_ICON_SIZE     = 36;
    /** 快捷栏高度（原 92dp，保留 92dp，因含两行 36dp 按钮 + 间距）。 */
    public static final int QUICKBAR_HEIGHT      = 92;
    /** 快捷栏按钮高度。 */
    public static final int QUICKBAR_KEY_HEIGHT  = 36;
    /** 状态指示灯尺寸。 */
    public static final int STATUS_DOT_SIZE      = 8;
    /** 设备徽章尺寸。 */
    public static final int DEVICE_BADGE_SIZE    = 42;
    /** 列表行距/卡片内边距的标准值。 */
    public static final int CARD_PADDING_VERTICAL = 12;
    public static final int CARD_PADDING_HORIZONTAL = 14;

    // ---------------------------------------------------------------------
    // 动画时长（ms）
    // ---------------------------------------------------------------------

    public static final int ANIM_FAST      = 150;  // hover、按钮点击反馈
    public static final int ANIM_NORMAL    = 200;  // 状态变化
    public static final int ANIM_PAGE      = 150;  // 页面切换 fade

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------

    /** ARGB 工具：把 hex 颜色附加 alpha 通道。 */
    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** 解析 #RRGGBB 或 #AARRGGBB 字符串。失败返回 BG_PRIMARY。 */
    public static int parseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return BG_PRIMARY;
        }
    }

    /** 设计系统 dump（调试用）。 */
    public static String dump() {
        return "DesignTokens v2: bg=" + Integer.toHexString(BG_PRIMARY)
            + " panel=" + Integer.toHexString(BG_SECONDARY)
            + " border=" + Integer.toHexString(BORDER_PRIMARY)
            + " accent=" + Integer.toHexString(ACCENT);
    }
}
