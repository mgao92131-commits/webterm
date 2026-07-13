package com.webterm.feature.terminal.upload;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

/** ACTION_OPEN_DOCUMENT 返回 Uri 的文件元数据查询与兜底（显示名 / 大小）。
 * 查询走 DocumentsContract；provider 不支持 query 时全部走兜底，不阻断上传。 */
public final class UploadDocumentMetadata {

    /** 文件元数据：displayName 保证非空（查询失败时兜底为 uri 末段），size 未知为 -1。 */
    public static final class Metadata {
        public final String displayName;
        public final long size;

        public Metadata(String displayName, long size) {
            this.displayName = displayName;
            this.size = size;
        }
    }

    private UploadDocumentMetadata() {}

    /** 通过 DocumentsContract 查询显示名与大小；任何异常都降级为兜底值。 */
    public static Metadata resolve(ContentResolver resolver, Uri uri) {
        String name = null;
        long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
            // 部分 provider 不支持 query 或缺列：走文件名/大小兜底，不阻断上传。
        }
        return normalize(name, size, uri == null ? null : uri.toString());
    }

    /** 纯函数：名称为空时兜底为 uri 末段，大小负数归一为 -1（便于 JVM 单测）。 */
    public static Metadata normalize(String displayName, long size, String uriString) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            name = fallbackName(uriString);
        }
        return new Metadata(name, size < 0 ? -1 : size);
    }

    /** 文件名兜底：取 uri 末段（content:// 末段常为文档 id，仍优于空名）。 */
    public static String fallbackName(String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            return "upload-file";
        }
        String value = uriString;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        String last = slash >= 0 ? value.substring(slash + 1) : value;
        return last.isEmpty() ? "upload-file" : last;
    }
}
