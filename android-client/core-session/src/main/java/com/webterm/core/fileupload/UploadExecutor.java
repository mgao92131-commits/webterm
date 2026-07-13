package com.webterm.core.fileupload;

import java.io.IOException;

/** 上传执行出口：FileUploadController 只依赖此接口，OkHttp 实现与 JVM 测试替身解耦。
 * 参照 filesend 的 FileDownloader 模式。 */
public interface UploadExecutor {
    /** 执行一次上传：打开流、构建请求、绑定 Call 到 task（供取消）、流式发送并解析响应。
     * 成功返回 UploadResult；业务失败抛 UploadException；网络/IO 失败抛 IOException。 */
    UploadResult execute(UploadTask task, UploadProgressListener progress) throws IOException;
}
