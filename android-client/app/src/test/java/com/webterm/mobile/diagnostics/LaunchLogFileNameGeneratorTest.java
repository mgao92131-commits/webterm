package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LaunchLogFileNameGeneratorTest {

    @Test
    public void launchGenerator_keepsOneNameForTheEntireProcess() {
        LaunchLogFileNameGenerator generator = new LaunchLogFileNameGenerator(0L);

        String first = generator.generateFileName(0, 1L);
        String second = generator.generateFileName(1, 2L);

        assertEquals(first, second);
        assertTrue(first.startsWith("webterm-"));
        assertTrue(first.endsWith(".log"));
        assertFalse(generator.isFileNameChangeable());
    }

    @Test
    public void sessionKey_groupsXlogBackupsWithTheirLaunch() {
        assertEquals("webterm-20260717-141302-000.log",
            DiagnosticLogFiles.sessionKey("webterm-20260717-141302-000.log.bak.3"));
        assertEquals("webterm-20260717-141302-000.log",
            DiagnosticLogFiles.sessionKey("webterm-20260717-141302-000.log"));
    }
}
