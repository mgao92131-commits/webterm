package com.webterm.core.filesend;

import java.io.IOException;
import java.io.InputStream;

/** 文件下载抽象：返回 /api/file-send/{transferId} 的响应体输入流。
 * 真实实现（里程碑 C-2）负责构造 URL 与携带 transfer_token；控制器不关心 URL/鉴权细节。 */
public interface FileDownloader {
    /** 打开响应体输入流，调用方负责关闭。 */
    InputStream open(String transferId, String transferToken) throws IOException;
}
