package com.webterm.core.filesend;

import java.io.IOException;
import java.io.InputStream;

/** 文件下载抽象：返回 /api/file-send/{transferId} 的响应体输入流。
 * connectionKey 用于在多设备场景下定位正确的 baseUrl/cookie；控制器不关心 URL/鉴权细节。 */
public interface FileDownloader {
    /** 打开响应体输入流，调用方负责关闭（关闭时应同时释放底层 HTTP 连接）。 */
    InputStream open(String connectionKey, String transferId, String transferToken) throws IOException;
}
