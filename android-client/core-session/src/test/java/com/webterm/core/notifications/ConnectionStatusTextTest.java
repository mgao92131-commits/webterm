package com.webterm.core.notifications;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConnectionStatusTextTest {
    @Test
    public void titleIsStable() {
        assertEquals("WebTerm 设备在线", ConnectionStatusText.title());
    }

    @Test
    public void zeroDevicesShowsWaiting() {
        assertEquals("等待接收文件与代理通知", ConnectionStatusText.contentText(0));
    }

    @Test
    public void oneDeviceUsesSingular() {
        assertEquals("保持 1 台设备在线", ConnectionStatusText.contentText(1));
    }

    @Test
    public void multipleDevicesShowsCount() {
        assertEquals("保持 3 台设备在线", ConnectionStatusText.contentText(3));
    }
}
