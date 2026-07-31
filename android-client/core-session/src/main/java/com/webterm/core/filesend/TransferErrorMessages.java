package com.webterm.core.filesend;

/** 将内部错误码映射为用户可读中文提示。 */
public final class TransferErrorMessages {
    private TransferErrorMessages() {}

    public static String userMessage(String code) {
        String normalized = TransferErrorCode.normalize(code);
        switch (normalized) {
            case TransferErrorCode.NETWORK_CONNECTION_RESET:
                return "网络连接中断，文件未完整接收";
            case TransferErrorCode.NETWORK_UNEXPECTED_EOF:
                return "网络传输提前结束，文件未完整接收";
            case TransferErrorCode.NETWORK_TIMEOUT:
                return "网络超时，文件未完整接收";
            case TransferErrorCode.RELAY_STREAM_CLOSED:
                return "中转连接已断开，文件未完整接收";
            case TransferErrorCode.RELAY_AGENT_UNAVAILABLE:
            case TransferErrorCode.HTTP_AGENT_UNAVAILABLE:
                return "电脑端当前不可用，请确认 Agent 在线后重试";
            case TransferErrorCode.HTTP_UNAUTHORIZED:
            case TransferErrorCode.HTTP_FORBIDDEN:
                return "文件传输鉴权失败，请重新连接后重试";
            case TransferErrorCode.HTTP_NOT_FOUND:
                return "传输任务已失效，请重新发送文件";
            case TransferErrorCode.HTTP_BAD_GATEWAY:
            case TransferErrorCode.HTTP_SERVER_ERROR:
                return "中转或电脑端服务异常，请稍后重试";
            case TransferErrorCode.STAGING_CREATE_FAILED:
            case TransferErrorCode.STAGING_WRITE_FAILED:
            case TransferErrorCode.STAGING_FLUSH_FAILED:
            case TransferErrorCode.STAGING_CLOSE_FAILED:
                return "写入手机缓存失败";
            case TransferErrorCode.STAGING_DISK_FULL:
                return "手机缓存空间不足，无法接收文件";
            case TransferErrorCode.SIZE_MISMATCH:
                return "文件大小校验失败";
            case TransferErrorCode.HASH_MISMATCH:
                return "文件完整性校验失败";
            case TransferErrorCode.STAGING_COMMIT_FAILED:
                return "缓存文件提交失败";
            case TransferErrorCode.STORAGE_PERMISSION_DENIED:
                return "下载目录授权已失效，请重新选择目录";
            case TransferErrorCode.STORAGE_UNAVAILABLE:
                return "下载目录不可用，请重新选择保存目录";
            case TransferErrorCode.TARGET_CREATE_FAILED:
                return "无法在下载目录创建文件，请重新选择保存目录";
            case TransferErrorCode.TARGET_OPEN_FAILED:
                return "无法打开下载目录中的目标文件，请重新选择保存目录";
            case TransferErrorCode.TARGET_WRITE_FAILED:
                return "文件已接收，但保存到下载目录失败";
            case TransferErrorCode.TARGET_DISK_FULL:
                return "下载目录空间不足";
            case TransferErrorCode.TARGET_FINALIZE_FAILED:
                return "文件已写入，但最终发布失败";
            case TransferErrorCode.TARGET_DELETE_FAILED:
                return "清理失败文件时出错";
            case TransferErrorCode.CONTROL_ACK_FAILED:
                return "文件已保存，但回传确认失败";
            case TransferErrorCode.UNKNOWN_IO_ERROR:
            default:
                return "文件接收失败（" + normalized + "）";
        }
    }
}
