package com.webterm.core.filesend;

/** 文件接收任务所处的细粒度阶段，用于失败诊断与通知文案。 */
public enum TransferPhase {
    PREPARING,
    OPENING_HTTP,
    RECEIVING,
    CLOSING_STAGING,
    VERIFYING_SIZE,
    VERIFYING_HASH,
    COMMITTING_STAGING,
    CREATING_TARGET,
    PUBLISHING,
    FINALIZING_TARGET,
    SENDING_RESULT,
    COMPLETED
}
