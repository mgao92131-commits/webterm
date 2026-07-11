package com.webterm.mobile.device;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import com.webterm.core.config.ServerConfigStore;
import com.webterm.core.filesend.ReceivedFilePublisher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 将完成校验的 staging 文件发布到 SAF 目录，未设置 SAF 时发布到系统 Downloads/WebTerm。 */
final class SafFilePublisher implements ReceivedFilePublisher {
    private static final String SUBDIRECTORY = "WebTerm";

    private final Context context;
    private final ServerConfigStore configStore;

    SafFilePublisher(Context context, ServerConfigStore configStore) {
        this.context = context.getApplicationContext();
        this.configStore = configStore;
    }

    @Override
    public boolean isReady() {
        String tree = configStore.getDownloadDirUri();
        if (tree == null || tree.isEmpty()) return true;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(tree));
            return root != null && root.canWrite();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public String publish(File stagingFile) throws IOException {
        String tree = configStore.getDownloadDirUri();
        if (tree != null && !tree.isEmpty()) {
            return publishToTree(stagingFile, tree);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishToMediaStore(stagingFile);
        }
        return publishToLegacyDownloads(stagingFile);
    }

    private String publishToTree(File stagingFile, String treeUri) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri));
        if (root == null || !root.canWrite()) throw new IOException("storage_unavailable");
        DocumentFile dir = root.findFile(SUBDIRECTORY);
        if (dir == null) dir = root.createDirectory(SUBDIRECTORY);
        if (dir == null || !dir.canWrite()) throw new IOException("storage_unavailable");
        String name = uniqueDocumentName(dir, stagingFile.getName());
        DocumentFile target = dir.createFile("application/octet-stream", name);
        if (target == null) throw new IOException("create_target_failed");
        try (InputStream in = new FileInputStream(stagingFile);
             OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "w")) {
            if (out == null) throw new IOException("open_target_failed");
            copy(in, out);
        } catch (IOException e) {
            target.delete();
            throw e;
        }
        deleteStaging(stagingFile);
        return SUBDIRECTORY + "/" + name;
    }

    private String publishToMediaStore(File stagingFile) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = stagingFile.getName();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIRECTORY);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("create_target_failed");
        try (InputStream in = new FileInputStream(stagingFile);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("open_target_failed");
            copy(in, out);
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        deleteStaging(stagingFile);
        return Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIRECTORY + "/" + name;
    }

    @SuppressWarnings("deprecation")
    private String publishToLegacyDownloads(File stagingFile) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("storage_unavailable");
        File target = uniqueFile(dir, stagingFile.getName());
        try (InputStream in = new FileInputStream(stagingFile);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
        deleteStaging(stagingFile);
        return Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIRECTORY + "/" + target.getName();
    }

    private static String uniqueDocumentName(DocumentFile dir, String name) {
        if (dir.findFile(name) == null) return name;
        String base = baseName(name);
        String ext = extension(name);
        for (int i = 1; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (dir.findFile(candidate) == null) return candidate;
        }
        return name;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String base = baseName(name);
        String ext = extension(name);
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return candidate;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static void deleteStaging(File file) {
        if (file.exists()) {
            // staging 清理不应把已成功发布的用户文件变成失败结果；服务启动时会再次清理残留。
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
