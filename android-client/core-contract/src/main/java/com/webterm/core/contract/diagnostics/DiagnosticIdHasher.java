package com.webterm.core.contract.diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 诊断标识哈希工具：server/deviceId/channelId 等标识一律以
 * SHA-256(salt + ':' + value) 截断 12 位 hex 的形式出现在日志与导出包中。
 * 进程级 salt 在进程启动时随机生成且不落地，保证同次运行内同一标识可关联、
 * 跨运行不可关联；导出时使用每次导出随机生成的 salt，保证跨导出包不可关联。
 */
public final class DiagnosticIdHasher {
    /** 对外输出的 hash 长度（hex 字符数）。 */
    public static final int HASH_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 进程级随机 salt：进程生命周期内稳定，不写入任何文件。 */
    private static final String PROCESS_SALT = randomSalt();

    private DiagnosticIdHasher() {}

    /** 进程级稳定 hash：同次运行内同一标识输出一致，用于日志事件字段。 */
    public static String processHash(String value) {
        return hash(PROCESS_SALT, value);
    }

    /** SHA-256(salt + ':' + value) 的前 {@value #HASH_LENGTH} 位 hex；value 为 null/空时返回 ""。 */
    public static String hash(String salt, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((salt + ':' + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(HASH_LENGTH);
            for (byte b : hashed) {
                if (out.length() >= HASH_LENGTH) break;
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                if (out.length() < HASH_LENGTH) {
                    out.append(Character.forDigit(b & 0xF, 16));
                }
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有受支持的 Android/JVM 上均可用；兜底返回空串，绝不回退为原值。
            return "";
        }
    }

    /** 生成 16 字节随机 salt 的 hex 形式（每次导出生成一次）。 */
    public static String randomSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
