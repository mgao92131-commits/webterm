package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** DiagnosticLogFiles.planDeletions 的纯逻辑测试：批次约束与字节/数量约束。 */
public class DiagnosticLogFilesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** 创建指定大小的文件并设置递增的 lastModified（序号越小越旧）。 */
    private File logFile(String name, long bytes, int ageIndex) throws IOException {
        File file = folder.newFile(name);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(bytes);
        }
        assertTrue(file.setLastModified(1_000_000L + ageIndex * 1000L));
        return file;
    }

    private static List<String> namesOf(List<File> files) {
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName());
        }
        return names;
    }

    @Test
    public void planDeletions_exceedingTotalBytesDeletesOldestFirst() throws IOException {
        // 5 个文件各 1 MiB，不同批次：总量 5 MiB 超 4 MiB，删最旧 1 个后正好达标。
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            files.add(logFile("webterm-s" + i + ".log", 1024 * 1024, i));
        }

        List<File> deletions = DiagnosticLogFiles.planDeletions(files);

        assertEquals(List.of("webterm-s0.log"), namesOf(deletions));
    }

    @Test
    public void planDeletions_exceedingFileCountDeletesOldestFirst() throws IOException {
        // 5 个小文件同一批次：批次约束不触发，数量约束删最旧 1 个。
        List<File> files = new ArrayList<>();
        files.add(logFile("webterm-s0.log", 100, 0));
        files.add(logFile("webterm-s0.log.bak.1", 100, 1));
        files.add(logFile("webterm-s0.log.bak.2", 100, 2));
        files.add(logFile("webterm-s0.log.bak.3", 100, 3));
        files.add(logFile("webterm-s0.log.bak.4", 100, 4));

        List<File> deletions = DiagnosticLogFiles.planDeletions(files);

        assertEquals(List.of("webterm-s0.log"), namesOf(deletions));
    }

    @Test
    public void planDeletions_sessionConstraintStillApplies() throws IOException {
        // 5 个批次各带一个 .bak：批次约束删最旧批次（主文件与备份一起删）。
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            files.add(logFile("webterm-s" + i + ".log", 100, i * 2));
            files.add(logFile("webterm-s" + i + ".log.bak.1", 100, i * 2 + 1));
        }

        List<File> deletions = DiagnosticLogFiles.planDeletions(files);

        // 批次约束删 s0 两个文件；剩余 8 个文件仍超数量约束，继续从最旧（s1 批次）删到 4 个。
        assertEquals(List.of(
            "webterm-s0.log", "webterm-s0.log.bak.1",
            "webterm-s1.log", "webterm-s1.log.bak.1",
            "webterm-s2.log", "webterm-s2.log.bak.1"), namesOf(deletions));
    }

    @Test
    public void planDeletions_exactlyAtBudgetDeletesNothing() throws IOException {
        // 4 个文件、合计正好 4 MiB：两个约束都未超出，不删。
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(logFile("webterm-s" + i + ".log", 1024 * 1024, i));
        }

        List<File> deletions = DiagnosticLogFiles.planDeletions(files);

        assertTrue(deletions.isEmpty());
    }

    @Test
    public void planDeletions_emptyInputDeletesNothing() {
        // 空目录（无日志文件）：不应产生任何删除。
        assertTrue(DiagnosticLogFiles.planDeletions(new ArrayList<>()).isEmpty());
    }
}
