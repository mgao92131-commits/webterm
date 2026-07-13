# 修复计划: 解决 Android 客户端后台断连导致通知延迟接收的问题

## 1. 背景与原因分析

目前 WebTerm Android 客户端（`android-client`）在退到后台或手机锁屏一段时间后，会出现接收不到代理通知和文件传输请求的问题。一旦将 App 重新带回前台，积压的通知就会瞬间涌出。

经分析，主要原因为：
1. **CPU 挂起导致心跳丢失**：前台服务 `WebTermDeviceService` 虽为前台服务，但在设备休眠时没有持有 `WakeLock`。这导致 `OkHttpClient` 的 15 秒 WebSocket 心跳（Ping）定时器在 CPU 睡眠时被挂起，无法向 Relay 服务器发送心跳。
2. **NAT 连接老化（静默断连）**：若超过数分钟没有网络包交互，运营商或路由器的 NAT 映射会失效，物理长连接已实际断开，但客户端与服务端在没有发送数据时均无法立即感知。
3. **网络恢复无感知**：`WebTermDeviceService` 缺乏对网络可用性变化的监听。当手机网络切换或断网重连后，服务无法秒级触发重连，只能等待指数退避定时器到期。
4. **系统电池优化**：Android 的 Doze 模式会强制冻结后台应用的网络连接。

---

## 2. 改造目标

通过三个层次的改造，保障后台连接的稳定性与通知的即时性：
1. **CPU 级保障**：在 `WebTermDeviceService` 中申请 `WakeLock`，保持 CPU 在后台微弱唤醒以维持 15s 心跳。
2. **网络级保障**：在 `WebTermDeviceService` 中监听 `NetworkCallback`，网络恢复时立即主动执行重连。
3. **系统级保障**：在 UI 设置中提供“忽略电池优化”引导，提示用户将 App 设置为“无限制”以绕过系统后台挂起。

---

## 3. 详细设计与代码变更

### 3.1 权限声明 (`AndroidManifest.xml`)
在 [AndroidManifest.xml](file:///Users/gao/Documents/webterm-clone/android-client/app/src/main/AndroidManifest.xml) 中增加两项权限：
```xml
<!-- 允许后台服务持有唤醒锁，保持心跳发送 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
<!-- 允许应用请求忽略电池优化以保障后台稳定性 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### 3.2 服务层优化 (`WebTermDeviceService.java`)
修改 [WebTermDeviceService.java](file:///Users/gao/Documents/webterm-clone/android-client/app/src/main/java/com/webterm/mobile/device/WebTermDeviceService.java)：
1. **引入 WakeLock**：
   - 声明 `PowerManager.WakeLock wakeLock` 变量。
   - 在 `onCreate()` 里：
     ```java
     PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
     if (pm != null) {
         wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebTerm:DeviceServiceWakeLock");
         try { wakeLock.acquire(); } catch (SecurityException e) { Log.e("DeviceService", "WakeLock acquire failed", e); }
     }
     ```
   - 在 `onDestroy()` 里：
     ```java
     if (wakeLock != null) {
         try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
         wakeLock = null;
     }
     ```
2. **引入 NetworkCallback 监听**：
   - 声明 `ConnectivityManager.NetworkCallback networkCallback` 变量。
   - 在 `onCreate()` 里：
     ```java
     ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
     if (cm != null) {
         networkCallback = new ConnectivityManager.NetworkCallback() {
             @Override
             public void onAvailable(Network network) {
                 new Handler(getMainLooper()).post(() -> {
                     Log.i("DeviceService", "Network recovery detected, reconnecting all devices...");
                     for (RelayMuxSessionManager manager : managers.values()) {
                         manager.forceReconnect("network-recovery");
                     }
                     refreshConnections();
                 });
             }
         };
         try {
             NetworkRequest request = new NetworkRequest.Builder()
                 .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                 .build();
             cm.registerNetworkCallback(request, networkCallback);
         } catch (Exception e) {
             Log.e("DeviceService", "Register NetworkCallback failed", e);
         }
     }
     ```
   - 在 `onDestroy()` 里注销：
     ```java
     if (networkCallback != null) {
         try {
             ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
             if (cm != null) cm.unregisterNetworkCallback(networkCallback);
         } catch (Exception ignored) {}
         networkCallback = null;
     }
     ```

### 3.3 设置 UI 引导优化 (`SettingsDialogHelper.java` 与 `AppFlowCoordinator.java`)
修改 [SettingsDialogHelper.java](file:///Users/gao/Documents/webterm-clone/android-client/app/src/main/java/com/webterm/mobile/ui/dialog/SettingsDialogHelper.java)：
1. 在 `Host` 接口中加入关于电池优化的判断和触发方法：
   ```java
   public interface Host {
       // ... 现有方法
       boolean isBatteryOptimizationIgnored();
       void requestIgnoreBatteryOptimization();
   }
   ```
2. 在弹窗布局中加入一个“后台持续连接”的设置行（使用 CheckBox 或 Switch，或者只读 TextView 指示当前状态并支持点击跳转）：
   ```java
   // ----------------- 后台连接（电池优化白名单） -----------------
   LinearLayout batteryRow = new LinearLayout(activity);
   batteryRow.setOrientation(LinearLayout.HORIZONTAL);
   batteryRow.setGravity(Gravity.CENTER_VERTICAL);
   batteryRow.setPadding(0, 0, 0, UIUtils.dp(activity, DesignTokens.SPACE_5));

   TextView batteryLabel = new TextView(activity);
   batteryLabel.setText("后台持续连接");
   batteryLabel.setTextColor(DesignTokens.TEXT_PRIMARY);
   batteryLabel.setTextSize(DesignTokens.TEXT_BODY_SIZE);
   batteryRow.addView(batteryLabel, new LinearLayout.LayoutParams(0, -2, 1));

   TextView batteryStatus = new TextView(activity);
   boolean isIgnored = host.isBatteryOptimizationIgnored();
   batteryStatus.setText(isIgnored ? "已开启" : "未开启 (去设置)");
   batteryStatus.setTextColor(isIgnored ? DesignTokens.TEXT_SECONDARY : DesignTokens.TEXT_DANGER); // 自定义颜色
   batteryStatus.setTextSize(DesignTokens.TEXT_LABEL_SIZE);
   batteryRow.addView(batteryStatus, new LinearLayout.LayoutParams(-2, -2));

   if (!isIgnored) {
       batteryRow.setOnClickListener((v) -> {
           host.requestIgnoreBatteryOptimization();
           dialog.dismiss(); // 跳转时关闭弹窗
       });
   }
   container.addView(batteryRow);
   ```

修改 [AppFlowCoordinator.java](file:///Users/gao/Documents/webterm-clone/android-client/app/src/main/java/com/webterm/mobile/ui/AppFlowCoordinator.java)：
1. 实现 `SettingsDialogHelper.Host` 的新方法：
   ```java
   @Override
   public boolean isBatteryOptimizationIgnored() {
       PowerManager pm = (PowerManager) mActivity.getSystemService(Context.POWER_SERVICE);
       if (pm == null) return true;
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           return pm.isIgnoringBatteryOptimizations(mActivity.getPackageName());
       }
       return true;
   }

   @Override
   public void requestIgnoreBatteryOptimization() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           try {
               Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
               intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
               mActivity.startActivity(intent);
           } catch (Exception e) {
               Toast.makeText(mActivity, "打开设置失败，请手动在系统设置中允许后台运行", Toast.LENGTH_LONG).show();
           }
       }
   }
   ```

---

## 4. 验证与测试步骤

1. **编译运行验证**：运行 `./gradlew :app:assembleRelease` 或使用 IDE 构建安装，确认无编译错误。
2. **黑屏/后台连通性测试**：
   - 打开 WebTerm Android 客户端并确保已登录中转，状态处于“已连接”。
   - 将应用退到后台并关闭屏幕，静置 15 分钟以上。
   - 使用 PC 端发送 Hook 代理通知或尝试发送文件，观察 Android 手机能否立刻弹出通知。
3. **断网切换测试**：
   - 处于后台静默状态时关闭 Wi-Fi（切换到移动网络），再开启 Wi-Fi，观察 Logcat 中是否会立即打印 `Network recovery detected, reconnecting all devices...` 并快速重连成功。
4. **电池优化白名单测试**：
   - 打开设置弹窗，查看“后台持续连接”状态是否正确反映系统设置。
   - 点击“未开启 (去设置)”跳转，同意系统弹出的忽略电池优化申请，返回 App 后确认状态已变更为“已开启”。
