package com.webterm.core.filesend;

import org.json.JSONObject;

/** 设备级 mux control 发送通道（由 RelayMuxSessionManager 适配）。 */
public interface ControlSender {
    /** 发送一条设备级控制消息；返回是否成功写入底层 mux。 */
    boolean sendControl(JSONObject msg);
}
