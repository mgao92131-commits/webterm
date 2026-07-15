# Android 文件接收设备选择实施计划

1. 扩展 mux control handler，使上层同时获得消息来源 sender；新增 `client.register`、`client.active` 与 `client.registered` 常量和测试。
2. 在 Go 端建立稳定 `client_id` 接收端目录，支持同 ID 覆盖、按实例注销、在线列表、名称/短 ID解析和最近设备选择。
3. FileSendTask 绑定目标 ClientID；删除随机 Relay stream ID 注册和单 sender 兜底；校验所有状态回执来源。
4. Relay Agent 每次物理连接退出时关闭其全部 WS/HTTP stream，覆盖异常断网和重连残留。
5. Android 持久化 ClientID，mux 连通后先注册，再恢复 manager/terminal channel；App 前台时上报活跃状态。
6. 本地 CLI 协议新增设备列表和目标 selector；实现 `webterm devices`、`--json`、`--online` 与 `webterm send --device`。
7. 增加 Go/Android 单元测试和 Relay 集成测试：浏览器与 Android 并存、多 Android、重连覆盖、延迟注销、错误来源回执。
8. 运行 Go、Android 测试，构建 Agent 与 release APK，重启运行时并真实发送 APK，最终以 Android `saved` 回执作为验收。
