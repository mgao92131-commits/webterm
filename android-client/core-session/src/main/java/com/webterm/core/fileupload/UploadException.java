package com.webterm.core.fileupload;

import java.io.IOException;

/** 上传业务失败：对应服务端非 2xx JSON {"code","message"}。
 * message 是服务端给出的中文文案，可直接展示给用户。 */
public final class UploadException extends IOException {
    /** 服务端错误码，例如 UPLOAD_DIRECTORY_NOT_WRITABLE；无法解析时为 INTERNAL_ERROR。 */
    public final String code;
    public final int httpStatus;

    public UploadException(String code, String message, int httpStatus) {
        super(message);
        this.code = code == null || code.isEmpty() ? "INTERNAL_ERROR" : code;
        this.httpStatus = httpStatus;
    }
}
