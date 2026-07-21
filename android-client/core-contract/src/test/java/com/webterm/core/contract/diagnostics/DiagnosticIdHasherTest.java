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
}
