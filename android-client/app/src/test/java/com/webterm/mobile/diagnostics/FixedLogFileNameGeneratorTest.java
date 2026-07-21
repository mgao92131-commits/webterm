package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** 固定全局文件名：所有启动共享 webterm.log，XLog 在其上做 .bak.1~3 有界滚动。 */
public class FixedLogFileNameGeneratorTest {

    @Test
    public void fixedGenerator_returnsSameNameAcrossCallsAndLevels() {
        FixedLogFileNameGenerator generator = new FixedLogFileNameGenerator();

        assertEquals("webterm.log", generator.generateFileName(0, 1L));
        assertEquals("webterm.log", generator.generateFileName(5, 999_999L));
        assertFalse(generator.isFileNameChangeable());
    }
}
