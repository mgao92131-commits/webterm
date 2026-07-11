package com.webterm.core.filesend;

import java.io.File;
import java.io.IOException;

/** 将已校验的 staging 文件发布到用户可见目标目录。 */
public interface ReceivedFilePublisher {
    /** 在 accepted 之前检查目标目录或其授权是否可用。 */
    boolean isReady();

    /** 发布完成后返回展示给用户和 Go 端的保存路径。实现必须在成功时删除 staging 文件。 */
    String publish(File stagingFile) throws IOException;
}
