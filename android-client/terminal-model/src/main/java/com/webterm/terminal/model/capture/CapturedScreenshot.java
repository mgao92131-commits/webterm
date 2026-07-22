package com.webterm.terminal.model.capture;

/**
 * 主线程截图结果：仅承载已下界的 ARGB 像素字节与尺寸，PNG 压缩/SHA/写文件由控制器在
 * 后台线程完成。这样主线程只做 View.draw + 像素拷贝（且按 maxPixels 下界缩放），
 * 避免整屏 bitmap + PNG 压缩长时间占用主线程导致卡顿/OOM。
 */
public final class CapturedScreenshot {
    public final byte[] argbPixels; // 长度 = width*height*4
    public final int width;
    public final int height;
    public final int originalWidth;
    public final int originalHeight;
    public final boolean scaled;

    public CapturedScreenshot(byte[] argbPixels, int width, int height,
                              int originalWidth, int originalHeight, boolean scaled) {
        this.argbPixels = argbPixels;
        this.width = width;
        this.height = height;
        this.originalWidth = originalWidth;
        this.originalHeight = originalHeight;
        this.scaled = scaled;
    }
}
