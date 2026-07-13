package com.webterm.core.filesend;

/** file_send 控制协议常量与接收端状态机。与 go-core/internal/filesend 保持一致。 */
public final class FileSendProtocol {
    private FileSendProtocol() {}

    public static final String TYPE_OFFER = "file_send.offer";
    public static final String TYPE_ACCEPTED = "file_send.accepted";
    public static final String TYPE_REJECTED = "file_send.rejected";
    public static final String TYPE_PROGRESS = "file_send.progress";
    public static final String TYPE_SAVING = "file_send.saving";
    public static final String TYPE_SAVED = "file_send.saved";
    public static final String TYPE_FAILED = "file_send.failed";
    public static final String TYPE_CANCELLED = "file_send.cancelled";

    public enum Status {
        CREATED,
        ACCEPTED,
        RECEIVING,
        SAVING,
        SAVED,
        REJECTED,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == SAVED || this == REJECTED || this == FAILED || this == CANCELLED;
        }
    }
}
