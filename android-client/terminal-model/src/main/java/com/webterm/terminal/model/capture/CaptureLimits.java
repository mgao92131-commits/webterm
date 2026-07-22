package com.webterm.terminal.model.capture;

/** 一次捕获的有界预算（与 Agent 端 terminalcapture.Limits 对应）。达到上限丢最旧并置截断标志。 */
public final class CaptureLimits {
    public final long maxDurationMillis;
    public final int maxAndroidWireBytes;
    public final int maxStructuredFrames;
    public final int maxScreenshots;

    public CaptureLimits(long maxDurationMillis, int maxAndroidWireBytes,
                         int maxStructuredFrames, int maxScreenshots) {
        this.maxDurationMillis = maxDurationMillis > 0 ? maxDurationMillis : 30_000L;
        this.maxAndroidWireBytes = maxAndroidWireBytes > 0 ? maxAndroidWireBytes : (4 << 20);
        this.maxStructuredFrames = maxStructuredFrames > 0 ? maxStructuredFrames : 256;
        this.maxScreenshots = maxScreenshots > 0 ? maxScreenshots : 3;
    }

    public static CaptureLimits defaults() {
        return new CaptureLimits(30_000L, 4 << 20, 256, 3);
    }
}
