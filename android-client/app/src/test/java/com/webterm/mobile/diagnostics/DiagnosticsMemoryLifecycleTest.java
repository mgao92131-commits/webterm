package com.webterm.mobile.diagnostics;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** DiagnosticsMemory 生命周期：重复初始化不产生多个 Ring。 */
public class DiagnosticsMemoryLifecycleTest {

    @After
    public void tearDown() {
        DiagnosticsMemory.shutdownForTest();
    }

    @Test
    public void initIsIdempotent() {
        assertTrue(DiagnosticsMemory.init());
        DiagnosticMemoryRing first = DiagnosticMemoryRing.getInstance();
        assertTrue(DiagnosticsMemory.init());
        assertTrue(first == DiagnosticMemoryRing.getInstance());
    }

    @Test
    public void shutdownForTestResetsRing() {
        DiagnosticsMemory.init();
        DiagnosticMemoryRing first = DiagnosticMemoryRing.getInstance();
        DiagnosticsMemory.shutdownForTest();
        DiagnosticsMemory.init();
        assertNotSame(first, DiagnosticMemoryRing.getInstance());
    }
}
