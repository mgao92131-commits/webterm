package com.webterm.mobile.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.webterm.core.api.WebTermUrls;
import com.webterm.core.agentnotify.AgentAlertSink;
import com.webterm.core.agentnotify.AgentNotificationController;
import com.webterm.core.agentnotify.AgentProtocol;
import com.webterm.core.agentnotify.DedupeStore;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.filesend.ControlSender;
import com.webterm.core.filesend.ControlSenderLookup;
import com.webterm.core.filesend.FileDownloader;
import com.webterm.core.filesend.FileReceiveController;
import com.webterm.core.filesend.FileSendProtocol;
import com.webterm.core.filesend.OkHttpFileDownloader;
import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/** 长期运行的设备服务：为每个已配置设备维持一条 mux 长连，并把 file_send.offer 路由到接收控制器。
 * 连接由共享的 RelayMuxSessionRegistry 持有（与终端复用同一条 mux）；本服务只负责
 * “确保在线 + 绑定设备级控制监听”，销毁时不强行 stop 共享连接。 */
@AndroidEntryPoint
public final class WebTermDeviceService extends Service {
    private static final String CHANNEL_ID = "webterm.device";
    private static final int NOTIFICATION_ID = 1001;

    @Inject RelayMuxSessionRegistry registry;
    @Inject OkHttpClient http;
    @Inject Executor ioExecutor;
    @Inject ServerConfigManager configManager;

    private final ConcurrentHashMap<String, RelayMuxSessionManager> managers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerConfig> configs = new ConcurrentHashMap<>();
    private FileReceiveController controller;
    private AgentNotificationController agentController;

    public static void start(Context context) {
        Intent intent = new Intent(context, WebTermDeviceService.class);
        androidx.core.content.ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        File receiveDir = resolveReceiveDir();
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

        DedupeStore dedupe = new DedupeStore(
            new File(getFilesDir(), "agent-notif-dedup.json"),
            DedupeStore.DEFAULT_TTL_MILLIS,
            DedupeStore.DEFAULT_MAX_ENTRIES,
            System::currentTimeMillis);
        AgentAlertSink sink = (connectionKey, sessionId, eventId, level, title, message) ->
            android.util.Log.i("WebTermDevice", "agent alert level=" + level + " session=" + sessionId);
        agentController = new AgentNotificationController(lookup, sink, dedupe);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
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
        // 共享连接不归本服务独占，仅解绑设备级监听，避免影响终端会话。
        for (RelayMuxSessionManager manager : managers.values()) {
            manager.setControlListener(null);
        }
        managers.clear();
        configs.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void refreshConnections() {
        configManager.load();
        for (ServerConfig config : configManager.servers()) {
            String deviceId = config.getDeviceId();
            String url = config.getUrl();
            if (deviceId == null || deviceId.isEmpty() || url == null || url.isEmpty()) {
                continue;
            }
            String key = connectionKey(url, deviceId);
            RelayMuxSessionManager manager = registry.forDevice(url, config.getCookie(), deviceId);
            managers.put(key, manager);
            configs.put(key, config);
            manager.setControlListener(msg -> routeControl(key, msg));
            manager.start();
        }
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
        RelayMuxSessionManager manager = managers.get(connectionKey);
        if (manager == null) return null;
        return manager::sendControl;
    }

    private static String connectionKey(String baseUrl, String deviceId) {
        // 必须与 RelayMuxSessionRegistry.key() 保持一致：cookie 不参与设备身份。
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + deviceId;
    }

    private File resolveReceiveDir() {
        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) {
            base = getFilesDir();
        }
        File dir = new File(base, "webterm-incoming");
        // 目录创建失败时控制器仍可用；写入时若失败会按 io_error 回报。
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WebTerm 设备连接",
                    NotificationManager.IMPORTANCE_MIN);
                channel.setDescription("保持与 PC 的长期连接以接收文件与代理通知");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("WebTerm 设备在线")
            .setContentText("等待接收文件与代理通知")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
    }
}
