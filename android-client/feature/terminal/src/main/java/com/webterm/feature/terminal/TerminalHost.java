package com.webterm.feature.terminal;

import androidx.annotation.Nullable;

import com.webterm.core.fileupload.FileUploadController;

/**
 * Host interface for TerminalFragment to communicate with its Activity
 * for starting terminal sessions.
 */
public interface TerminalHost {
    /**
     * Start a terminal session within the given fragment.
     */
    void startTerminalInFragment(TerminalViewModel.TerminalSessionArgs args, TerminalFragment fragment);

    /**
     * Detach the fragment view from the terminal runtime without closing the runtime.
     */
    void detachTerminalFragment(TerminalFragment fragment);

    /**
     * 上传控制器入口：WebTermDeviceService.uploadController() 的页面侧桥接；
     * 设备服务未启动（或被回收）时返回 null。
     */
    @Nullable
    FileUploadController uploadController();
}
