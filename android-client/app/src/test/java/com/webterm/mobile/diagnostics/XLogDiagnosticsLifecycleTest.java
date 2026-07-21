package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Test;

/**
 * 周期 trim 执行器的生命周期测试：重复初始化不得泄漏多个 executor。
 * startPeriodicTrim 不依赖 Android Context，可在纯 JVM 单测中验证。
 */
public class XLogDiagnosticsLifecycleTest {

    @After
    public void tearDown() {
        XLogDiagnostics.shutdownForTest();
    }

    @Test
    public void repeatedStartShutsDownPreviousExecutor() {
        ScheduledExecutorService first = XLogDiagnostics.startPeriodicTrim(() -> {});
        ScheduledExecutorService second = XLogDiagnostics.startPeriodicTrim(() -> {});

        assertTrue("previous executor must be shut down on re-init", first.isShutdown());
        assertFalse("latest executor stays active", second.isShutdown());
    }

    @Test
    public void shutdownForTestStopsExecutorAndAllowsRestart() {
        ScheduledExecutorService first = XLogDiagnostics.startPeriodicTrim(() -> {});
        XLogDiagnostics.shutdownForTest();
        assertTrue(first.isShutdown());

        ScheduledExecutorService second = XLogDiagnostics.startPeriodicTrim(() -> {});
        assertFalse(second.isShutdown());
    }

    @Test
    public void stopIsIdempotent() {
        XLogDiagnostics.stopPeriodicTrim();
        XLogDiagnostics.stopPeriodicTrim(); // 无 executor 时不应抛异常
    }
}
