package com.webterm.core.filesend;

/** 多设备场景下按 connectionKey 查找对应的控制消息发送通道。 */
public interface ControlSenderLookup {
    /** 返回某设备连接的发送通道；若该连接当前不可用可返回 null（控制器据此判失败）。 */
    ControlSender senderFor(String connectionKey);
}
