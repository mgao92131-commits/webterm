package com.webterm.core.fileupload;

/** 上传成功结果：对应服务端 200 JSON {"fileName","relativePath","absolutePath","size"}。 */
public final class UploadResult {
    /** 实际落盘名（服务端去重后可能不是原始文件名）。 */
    public final String fileName;
    /** 相对上传目录的路径，例如 WebTermUploads/demo.zip，UI 展示用。 */
    public final String relativePath;
    public final String absolutePath;
    public final long size;

    public UploadResult(String fileName, String relativePath, String absolutePath, long size) {
        this.fileName = fileName == null ? "" : fileName;
        this.relativePath = relativePath == null ? "" : relativePath;
        this.absolutePath = absolutePath == null ? "" : absolutePath;
        this.size = size;
    }
}
