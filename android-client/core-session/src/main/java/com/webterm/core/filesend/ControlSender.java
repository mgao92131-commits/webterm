package com.webterm.core.filesend;

import org.json.JSONObject;

/** 设备级 control 发送通道（由 DeviceConnection 适配）。 */
public interface ControlSender {
    /** 发送一条设备级控制消息；返回是否成功写入底层设备连接。 */
    boolean sendControl(JSONObject msg);
}
