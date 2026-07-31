package com.webterm.core.filesend;

/** 文件传输结构化错误码。控制面 file_send.failed.error、通知与 CLI 共用同一套码。 */
public final class TransferErrorCode {
    private TransferErrorCode() {}

    public static final String NETWORK_CONNECTION_RESET = "NETWORK_CONNECTION_RESET";
    public static final String NETWORK_UNEXPECTED_EOF = "NETWORK_UNEXPECTED_EOF";
    public static final String NETWORK_TIMEOUT = "NETWORK_TIMEOUT";
    public static final String RELAY_STREAM_CLOSED = "RELAY_STREAM_CLOSED";
    public static final String RELAY_AGENT_UNAVAILABLE = "RELAY_AGENT_UNAVAILABLE";

    public static final String HTTP_UNAUTHORIZED = "HTTP_UNAUTHORIZED";
    public static final String HTTP_FORBIDDEN = "HTTP_FORBIDDEN";
    public static final String HTTP_NOT_FOUND = "HTTP_NOT_FOUND";
    public static final String HTTP_AGENT_UNAVAILABLE = "HTTP_AGENT_UNAVAILABLE";
    public static final String HTTP_BAD_GATEWAY = "HTTP_BAD_GATEWAY";
    public static final String HTTP_SERVER_ERROR = "HTTP_SERVER_ERROR";

    public static final String STAGING_CREATE_FAILED = "STAGING_CREATE_FAILED";
    public static final String STAGING_WRITE_FAILED = "STAGING_WRITE_FAILED";
    public static final String STAGING_FLUSH_FAILED = "STAGING_FLUSH_FAILED";
    public static final String STAGING_CLOSE_FAILED = "STAGING_CLOSE_FAILED";
    public static final String STAGING_DISK_FULL = "STAGING_DISK_FULL";

    public static final String SIZE_MISMATCH = "SIZE_MISMATCH";
    public static final String HASH_MISMATCH = "HASH_MISMATCH";
    public static final String STAGING_COMMIT_FAILED = "STAGING_COMMIT_FAILED";

    public static final String STORAGE_PERMISSION_DENIED = "STORAGE_PERMISSION_DENIED";
    public static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";
    public static final String TARGET_CREATE_FAILED = "TARGET_CREATE_FAILED";
    public static final String TARGET_OPEN_FAILED = "TARGET_OPEN_FAILED";
    public static final String TARGET_WRITE_FAILED = "TARGET_WRITE_FAILED";
    public static final String TARGET_DISK_FULL = "TARGET_DISK_FULL";
    public static final String TARGET_FINALIZE_FAILED = "TARGET_FINALIZE_FAILED";
    public static final String TARGET_DELETE_FAILED = "TARGET_DELETE_FAILED";

    public static final String CONTROL_ACK_FAILED = "CONTROL_ACK_FAILED";
    public static final String UNKNOWN_IO_ERROR = "UNKNOWN_IO_ERROR";

    /** 兼容旧协议码到新码的归一。 */
    public static String normalize(String code) {
        if (code == null || code.isEmpty()) return UNKNOWN_IO_ERROR;
        switch (code) {
            case "io_error":
                return UNKNOWN_IO_ERROR;
            case "size_mismatch":
                return SIZE_MISMATCH;
            case "hash_mismatch":
                return HASH_MISMATCH;
            case "rename_failed":
                return STAGING_COMMIT_FAILED;
            case "storage_unavailable":
                return STORAGE_UNAVAILABLE;
            case "create_target_failed":
                return TARGET_CREATE_FAILED;
            case "open_target_failed":
                return TARGET_OPEN_FAILED;
            default:
                return code;
        }
    }
}
