package com.webterm.core.contract.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticIdHasherTest {

    @Test
    public void processHashIsStableWithinProcess() {
        assertEquals(DiagnosticIdHasher.processHash("device-1"),
            DiagnosticIdHasher.processHash("device-1"));
    }

    @Test
    public void hashIsTwelveHexChars() {
        String hash = DiagnosticIdHasher.hash("salt", "value");
        assertEquals(DiagnosticIdHasher.HASH_LENGTH, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    public void emptyAndNullValuesHashToEmptyString() {
        assertEquals("", DiagnosticIdHasher.hash("salt", null));
        assertEquals("", DiagnosticIdHasher.hash("salt", ""));
        assertEquals("", DiagnosticIdHasher.processHash(null));
    }

    @Test
    public void differentSaltsProduceDifferentHashes() {
        assertNotEquals(DiagnosticIdHasher.hash("salt-a", "same-value"),
            DiagnosticIdHasher.hash("salt-b", "same-value"));
    }

    @Test
    public void differentValuesProduceDifferentHashes() {
        assertNotEquals(DiagnosticIdHasher.hash("salt", "value-a"),
            DiagnosticIdHasher.hash("salt", "value-b"));
    }

    @Test
    public void randomSaltIsHexAndUnique() {
        String first = DiagnosticIdHasher.randomSalt();
        String second = DiagnosticIdHasher.randomSalt();
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]+"));
        assertNotEquals(first, second);
    }

    /**
     * 跨端固定向量：Android {@code DiagnosticIdHasher.hash(salt, id)} 输出 12 位小写 hex。
     * Agent 侧 {@code validAndroidDiagnosticHash} 接受相同格式；进程 salt 随机故无法固定
     * processHash，但 {@code hash("testsalt", id)} 可跨语言复算。
     * <p>向量：SHA-256("testsalt:connection-id-vector-1") 截断 12 hex = {@code 687dccd95cb4}。
     */
    @Test
    public void fixedSaltHashIsTwelveLowercaseHexCrossVector() {
        String hash = DiagnosticIdHasher.hash("testsalt", "connection-id-vector-1");
        assertEquals(12, hash.length());
        assertTrue(hash.matches("[0-9a-f]{12}"));
        assertEquals("687dccd95cb4", hash);
    }

    @Test
    public void processHashMatchesAgentAcceptedFormat() {
        String hash = DiagnosticIdHasher.processHash("device-control-plane-id");
        assertEquals(DiagnosticIdHasher.HASH_LENGTH, hash.length());
        assertTrue("Agent validAndroidDiagnosticHash requires [0-9a-f]{12}",
            hash.matches("[0-9a-f]{12}"));
    }
}
