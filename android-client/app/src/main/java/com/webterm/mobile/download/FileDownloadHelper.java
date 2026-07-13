package com.webterm.mobile.download;

import android.app.Activity;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.core.config.ServerConfig;

public final class FileDownloadHelper {
    private final Activity activity;
    private final WebTermApi api;
    private final ServerConfigStore configStore;

    public FileDownloadHelper(Activity activity, WebTermApi api, ServerConfigStore configStore) {
        this.activity = activity;
        this.api = api;
        this.configStore = configStore;
    }

    public void startDownload(ServerConfig server, String downloadId, String fileName,
                              long fileSize, String sessionId) {
        // TODO: implement download progress handling
    }
}
