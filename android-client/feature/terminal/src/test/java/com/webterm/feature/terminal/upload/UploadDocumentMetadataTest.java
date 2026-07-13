package com.webterm.feature.terminal.upload;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 文件元数据兜底逻辑测试（ContentResolver 查询部分需真机，纯函数部分走 JVM 单测）。 */
public class UploadDocumentMetadataTest {

    @Test
    public void normalize_keepsQueriedNameAndSize() {
        UploadDocumentMetadata.Metadata meta =
            UploadDocumentMetadata.normalize("demo.zip", 123456, "content://x/document/1");
        assertEquals("demo.zip", meta.displayName);
        assertEquals(123456, meta.size);
    }

    @Test
    public void normalize_emptyName_fallsBackToUriLastSegment() {
        UploadDocumentMetadata.Metadata meta = UploadDocumentMetadata.normalize(
            "", 100, "content://com.android.providers.downloads.documents/document/1234");
        assertEquals("1234", meta.displayName);
        assertEquals(100, meta.size);
    }

    @Test
    public void normalize_nullName_fallsBackToUriLastSegment() {
        UploadDocumentMetadata.Metadata meta = UploadDocumentMetadata.normalize(
            null, -1, "content://x/raw:/storage/emulated/0/Download/demo.zip");
        assertEquals("demo.zip", meta.displayName);
        assertEquals(-1, meta.size);
    }

    @Test
    public void normalize_negativeSize_becomesUnknown() {
        assertEquals(-1, UploadDocumentMetadata.normalize("a.txt", -5, "content://x/1").size);
    }

    @Test
    public void fallbackName_trailingSlash_stripped() {
        assertEquals("dir", UploadDocumentMetadata.fallbackName("content://x/tree/dir/"));
    }

    @Test
    public void fallbackName_emptyUri_usesDefault() {
        assertEquals("upload-file", UploadDocumentMetadata.fallbackName(null));
        assertEquals("upload-file", UploadDocumentMetadata.fallbackName(""));
    }
}
