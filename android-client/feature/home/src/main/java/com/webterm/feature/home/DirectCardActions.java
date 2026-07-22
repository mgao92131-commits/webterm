package com.webterm.feature.home;

import com.webterm.core.config.ServerConfig;

/**
 * Direct 设备卡片右侧三点菜单的动作回调。由 HomeFragment 实现并委托给 HomeHost，
 * 避免 feature:home 直接依赖登录/存储/服务逻辑。
 */
public interface DirectCardActions {
    /** 编辑直连设备（地址/账户/密码），保存前重新登录验证。 */
    void onEditDirect(ServerConfig server);

    /** 重新连接：强制刷新 Cookie 并重建连接。 */
    void onReconnectDirect(ServerConfig server);

    /** 删除直连设备（仅移除 Android 端配置与连接，不关闭电脑上的终端会话）。 */
    void onDeleteDirect(ServerConfig server);
}
