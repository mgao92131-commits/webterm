package com.webterm.mobile.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.agentnotify.AgentAlertSink;
import com.webterm.core.agentnotify.AgentNotificationController;
import com.webterm.core.agentnotify.AgentProtocol;
import com.webterm.core.agentnotify.DedupeStore;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.core.filesend.ControlSender;
import com.webterm.core.filesend.ControlSenderLookup;
import com.webterm.core.filesend.FileDownloader;
import com.webterm.core.filesend.FileReceiveController;
import com.webterm.core.filesend.FileSendProtocol;
import com.webterm.core.filesend.OkHttpFileDownloader;
import com.webterm.core.filesend.TransferNotificationSink;
import com.webterm.core.fileupload.FileUploadController;
import com.webterm.core.fileupload.UploadNotificationSink;
import com.webterm.core.fileupload.UploadRequestExecutor;
import com.webterm.core.notifications.NotificationCommand;
import com.webterm.core.notifications.NotificationController;
import com.webterm.core.notifications.TerminalFocusStore;
import com.webterm.core.notifications.ConnectionStatusText;
import com.webterm.core.relay.RelayService;
import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionRegistry;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/** 长期运行的设备服务：为每个已配置设备维持一条 mux 长连，并把 file_send.offer 路由到接收控制器。
 * 连接由共享的 DeviceConnectionRegistry 持有（与终端复用同一条 mux）；本服务只负责
 * “确保在线 + 绑定设备级控制监听”，销毁时不强行 stop 共享连接。 */
@AndroidEntryPoint
public final class WebTermDeviceService extends Service {
    private static final String CHANNEL_ID = "webterm.device";
    private static final int NOTIFICATION_ID = 1001;

    /** 通知 “取消传输” action：renderer 通过 PendingIntent.getService 回投，本服务在
     * onStartCommand 中按方向分发：接收 -> FileReceiveController.cancel，
     * 上传 -> FileUploadController.cancel（避免同 id 误取消）。 */
    public static final String ACTION_CANCEL_TRANSFER = "webterm.action.CANCEL_TRANSFER";
    public static final String EXTRA_TRANSFER_ID = "webterm.extra.transfer_id";
    /** 取消动作方向：NotificationCommand.DIRECTION_RECEIVE / DIRECTION_UPLOAD；
     * 缺省（旧版通知）按接收处理。 */
    public static final String EXTRA_TRANSFER_DIRECTION = "webterm.extra.transfer_direction";
    /** 取消动作所属 connectionKey：上传方向必需（任务键 = connectionKey + sessionId）。 */
    public static final String EXTRA_CONNECTION_KEY = "webterm.extra.connection_key";

    /** 前台通知「全部停止」action：释放所有设备在线租约并退出前台。 */
    public static final String ACTION_STOP_ALL = "webterm.action.STOP_ALL_DEVICES";

    private static final String PREFS = "webterm.device_service";
    private static final String KEY_CONNECTIONS_ENABLED = "connections_enabled";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_CLIENT_NAME = "client_name";

    @Inject DeviceConnectionRegistry registry;
    @Inject OkHttpClient http;
    @Inject Executor ioExecutor;
    @Inject ServerConfigManager configManager;
    @Inject ServerConfigStore configStore;
    @Inject RelayService relayService;
    @Inject TerminalFocusStore terminalFocus;

    private final ConcurrentHashMap<String, DeviceConnection> managers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerConfig> configs = new ConcurrentHashMap<>();
    private final Set<String> relayConnectionKeys = ConcurrentHashMap.newKeySet();
    private FileReceiveController controller;
    private FileUploadController uploadController;
    private AgentNotificationController agentController;
    private NotificationController notifications;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;

    /** 当前存活的服务实例：本服务为未绑定的 started service，终端页/AppFlowCoordinator
     * 通过 uploadController() 访问上传控制器（页面重建后从 controller 重新订阅任务状态）。 */
    private static WebTermDeviceService activeInstance;

    /** 上传控制器访问入口；服务未启动（或被回收）时返回 null。 */
    @Nullable
    public static FileUploadController uploadController() {
        WebTermDeviceService instance = activeInstance;
        return instance == null ? null : instance.uploadController;
    }

    /** App 进入前台或发生真实用户操作时，更新所有在线远端连接的接收端活跃时间。 */
    public static void markActive() {
        WebTermDeviceService instance = activeInstance;
        if (instance == null) return;
        for (DeviceConnection manager : instance.managers.values()) manager.markClientActive();
    }

    public static void start(Context context) {
        if (!connectionsEnabled(context)) return;
        Intent intent = new Intent(context, WebTermDeviceService.class);
        androidx.core.content.ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        File receiveDir = resolveStagingDir();
        cleanupStaleParts(receiveDir);
        ControlSenderLookup lookup = this::senderFor;
        OkHttpFileDownloader.EndpointResolver resolver = new OkHttpFileDownloader.EndpointResolver() {
            @Override public String baseUrl(String connectionKey) {
                ServerConfig c = configs.get(connectionKey);
                return c == null ? null : c.getUrl();
            }
            @Override public String cookie(String connectionKey) {
                ServerConfig c = configs.get(connectionKey);
                return c == null ? null : c.getCookie();
            }
        };
        FileDownloader downloader = new OkHttpFileDownloader(http, resolver);
        controller = new FileReceiveController(receiveDir, lookup, downloader, ioExecutor);
        controller.setFilePublisher(new SafFilePublisher(this, configStore));

        // 上传控制器：与接收同一套 baseUrl/cookie 解析；流来源用 ContentResolver 按 Uri 打开。
        UploadRequestExecutor uploadExecutor = new UploadRequestExecutor(http,
            new UploadRequestExecutor.EndpointResolver() {
                @Override public String baseUrl(String connectionKey) {
                    ServerConfig c = configs.get(connectionKey);
                    return c == null ? null : c.getUrl();
                }
                @Override public String cookie(String connectionKey) {
                    ServerConfig c = configs.get(connectionKey);
                    return c == null ? null : c.getCookie();
                }
                @Override public String deviceId(String connectionKey) {
                    ServerConfig c = configs.get(connectionKey);
                    if (c == null || !c.isRelayDevice()) return null;
                    return c.getDeviceId();
                }
            },
            uri -> {
                java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uri));
                if (in == null) throw new java.io.IOException("无法打开待上传文件");
                return in;
            });
        uploadController = new FileUploadController(uploadExecutor, ioExecutor);
        activeInstance = this;

        DedupeStore dedupe = new DedupeStore(
            new File(getFilesDir(), "agent-notif-dedup.json"),
            DedupeStore.DEFAULT_TTL_MILLIS,
            DedupeStore.DEFAULT_MAX_ENTRIES,
            System::currentTimeMillis);
        notifications = new NotificationController(new AndroidNotificationRenderer(this));
        controller.setNotificationSink(new TransferNotificationSink() {
            @Override public void onProgress(String connectionKey, String transferId, String fileName, long bytes, long total) {
                notifications.postTransferProgress(connectionKey, transferId, fileName, bytes, total);
            }
            @Override public void onSaved(String connectionKey, String transferId, String fileName, String savedName) {
                notifications.postTransferSaved(connectionKey, transferId, fileName, savedName);
            }
            @Override public void onFailed(String connectionKey, String transferId, String fileName, String error) {
                notifications.postTransferFailed(connectionKey, transferId, fileName, error);
            }
            @Override public void onCancelled(String connectionKey, String transferId, String fileName) {
                notifications.postTransferCancelled(connectionKey, transferId, fileName);
            }
        });
        uploadController.setNotificationSink(new UploadNotificationSink() {
            @Override public void onProgress(String connectionKey, String sessionId, String fileName, long bytes, long total) {
                notifications.postUploadProgress(connectionKey, sessionId, fileName, bytes, total);
            }
            @Override public void onSucceeded(String connectionKey, String sessionId, String fileName, String relativePath) {
                notifications.postUploadSucceeded(connectionKey, sessionId, fileName, relativePath);
            }
            @Override public void onFailed(String connectionKey, String sessionId, String fileName, String error) {
                notifications.postUploadFailed(connectionKey, sessionId, fileName, error);
            }
            @Override public void onCancelled(String connectionKey, String sessionId, String fileName) {
                notifications.postUploadCancelled(connectionKey, sessionId, fileName);
            }
        });
        AgentAlertSink sink = (connectionKey, sessionId, eventId, importance, title, message) -> {
            if (!terminalFocus.isVisible(connectionKey, sessionId)) {
                notifications.postAgent(connectionKey, sessionId, importance, title, message);
            }
        };
        agentController = new AgentNotificationController(lookup, sink, dedupe);
        relayService.setDeviceListener(this::syncRelayDevices);

        // 申请 WakeLock 保证 CPU 在后台不休眠以维持 15s WebSocket 心跳
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebTerm:DeviceServiceWakeLock");
            try {
                wakeLock.acquire();
                Log.i("WebTermDeviceService", "Acquired WakeLock");
            } catch (SecurityException e) {
                Log.e("WebTermDeviceService", "Failed to acquire wake lock", e);
            }
        }

        // 注册网络监听，断网恢复时秒级主动重连
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    new Handler(getMainLooper()).post(() -> {
                        Log.i("WebTermDeviceService", "Network became available, refreshing connections...");
                        for (DeviceConnection manager : managers.values()) {
                            manager.forceReconnect("network-available");
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
                Log.e("WebTermDeviceService", "Failed to register network callback", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL_TRANSFER.equals(intent.getAction())) {
            String transferId = intent.getStringExtra(EXTRA_TRANSFER_ID);
            String direction = intent.getStringExtra(EXTRA_TRANSFER_DIRECTION);
            if (transferId != null && !transferId.isEmpty()) {
                if (NotificationCommand.DIRECTION_UPLOAD.equals(direction)) {
                    // 上传方向：transferId 承载 sessionId，任务键 = connectionKey + sessionId。
                    String connectionKey = intent.getStringExtra(EXTRA_CONNECTION_KEY);
                    if (connectionKey != null && !connectionKey.isEmpty()) {
                        uploadController.cancel(connectionKey, transferId);
                    }
                } else {
                    // 缺省方向按接收处理（兼容旧版通知的 PendingIntent）。
                    controller.cancel(transferId);
                }
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            stopAllDevices();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(managers.size()));
        refreshConnections();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        activeInstance = null;
        // 共享连接不归本服务独占，仅解绑设备级监听，避免影响终端会话。
        for (DeviceConnection manager : managers.values()) {
            manager.setControlListener(null);
        }
        managers.clear();
        configs.clear();
        relayConnectionKeys.clear();
        relayService.setDeviceListener(null);

        // 释放 WakeLock
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                    Log.i("WebTermDeviceService", "Released WakeLock");
                }
            } catch (Exception ignored) {}
            wakeLock = null;
        }

        // 注销网络监听
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {}
            networkCallback = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void refreshConnections() {
        if (!connectionsEnabled(this)) {
            stopAllDevices();
            return;
        }
        configManager.load();
        for (ServerConfig config : configManager.servers()) {
            if (config.isRelayMaster()) continue;
            connectDevice(config, false);
        }
        relayService.loadMasterFromServers(configManager.servers());
        relayService.start();
        updateConnectionNotification();
    }

    private void syncRelayDevices(List<ServerConfig> devices) {
        if (!connectionsEnabled(this)) return;
        configManager.save(); // Persist a refreshed Relay Master cookie before its next service restart.
        Set<String> nextKeys = new HashSet<>();
        for (ServerConfig config : devices) {
            String key = connectDevice(config, true);
            if (key != null) nextKeys.add(key);
        }
        for (String key : new HashSet<>(relayConnectionKeys)) {
            if (nextKeys.contains(key)) continue;
            DeviceConnection manager = managers.remove(key);
            configs.remove(key);
            if (manager != null) {
                manager.setControlListener(null);
                registry.releaseIfIdle(manager);
            }
        }
        relayConnectionKeys.clear();
        relayConnectionKeys.addAll(nextKeys);
        updateConnectionNotification();
    }

    @Nullable
    private String connectDevice(ServerConfig config, boolean relayDevice) {
        if (config == null) return null;
        String deviceId = config.getDeviceId();
        String url = config.getUrl();
        if (url == null || url.isEmpty()) {
            return null;
        }
		if (deviceId == null || deviceId.isEmpty()) {
            return null;
        }
        String key = connectionKey(url, deviceId);
        DeviceConnection manager = registry.forDevice(url, config.getCookie(), deviceId);
        manager.setClientRegistration(receiverClientId(this), receiverClientName(this));
        managers.put(key, manager);
        configs.put(key, config);
        manager.setControlListener(msg -> routeControl(key, msg));
        manager.start();
        if (relayDevice) relayConnectionKeys.add(key);
        return key;
    }

    /** 用当前在线设备数刷新持久前台通知（计数文案见 ConnectionStatusText）。 */
    private void updateConnectionNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(managers.size()));
    }

    /** 用户从持久通知选择「全部停止」：释放所有设备在线租约（经 releaseIfIdle 归还共享连接），
     * 清空路由并退出前台。这是一次显式用户动作，与 onDestroy 不强行 stop 共享连接不同。 */
    private void stopAllDevices() {
        preferences(this).edit().putBoolean(KEY_CONNECTIONS_ENABLED, false).apply();
        for (DeviceConnection manager : managers.values()) {
            manager.setControlListener(null);
            registry.releaseIfIdle(manager);
        }
        managers.clear();
        configs.clear();
        relayConnectionKeys.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void routeControl(String connectionKey, JSONObject msg) {
        if (msg == null) return;
        String type = msg.optString("type", "");
        if (FileSendProtocol.TYPE_OFFER.equals(type)) {
            controller.onOffer(connectionKey, msg);
        } else if (AgentProtocol.TYPE_AGENT_NOTIFICATION.equals(type)) {
            agentController.onNotification(connectionKey, msg);
        }
    }

    private ControlSender senderFor(String connectionKey) {
        DeviceConnection manager = managers.get(connectionKey);
        if (manager == null) return null;
        return manager::sendControl;
    }

    private static String connectionKey(String baseUrl, String deviceId) {
		// cookie 不参与设备身份，必须与 DeviceConnectionRegistry.key() 保持一致。
        return DeviceConnectionKeys.relay(baseUrl, deviceId);
    }

    private File resolveStagingDir() {
        File dir = new File(getCacheDir(), "file-send-staging");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String receiverClientId(Context context) {
        SharedPreferences prefs = preferences(context);
        String id = prefs.getString(KEY_CLIENT_ID, "");
        if (id != null && !id.isEmpty()) return id;
        id = "android_" + UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_CLIENT_ID, id).apply();
        return id;
    }

    private static String receiverClientName(Context context) {
        String saved = preferences(context).getString(KEY_CLIENT_NAME, "");
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String name = (manufacturer + " " + model).replaceAll("\\s+", " ").trim();
        return name.isEmpty() ? "Android" : name;
    }

    private static boolean connectionsEnabled(Context context) {
        return preferences(context).getBoolean(KEY_CONNECTIONS_ENABLED, true);
    }

    private static void cleanupStaleParts(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.lastModified() >= cutoff) continue;
            String name = file.getName();
            if (name.endsWith(".part") || name.endsWith(".part.meta.json")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WebTerm 设备连接",
                    NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("保持与 PC 的长期连接以接收文件与代理通知");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(int onlineCount) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ConnectionStatusText.title())
            .setContentText(ConnectionStatusText.contentText(onlineCount))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        if (onlineCount > 0) {
            builder.addAction(0, "全部停止", buildStopAllIntent());
        }
        return builder.build();
    }

    private PendingIntent buildStopAllIntent() {
        Intent intent = new Intent(this, WebTermDeviceService.class);
        intent.setAction(ACTION_STOP_ALL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, NOTIFICATION_ID, intent, flags);
    }
}
