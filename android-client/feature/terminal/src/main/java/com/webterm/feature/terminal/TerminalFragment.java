package com.webterm.feature.terminal;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.webterm.core.fileupload.FileUploadController;
import com.webterm.core.fileupload.UploadTask;
import com.webterm.feature.terminal.domain.TerminalRuntime;
import com.webterm.feature.terminal.upload.UploadConnectionKeys;
import com.webterm.feature.terminal.upload.UploadDocumentMetadata;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;

import org.json.JSONObject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * TerminalFragment provides the container for the terminal screen.
 * Uses TerminalViewModel for terminal session state and TerminalHost for
 * delegating terminal lifecycle to the Activity.
 *
 * 文件上传：本 Fragment 是 ACTION_OPEN_DOCUMENT 的 Activity Result 注册者；
 * 任务由 WebTermDeviceService 拥有的 FileUploadController 管理，页面只负责
 * 文件选择与浮层渲染（禁止向 PTY 写任何文本）。
 */
@AndroidEntryPoint
public final class TerminalFragment extends Fragment {

    private TerminalViewModel mViewModel;
    private TerminalHost mHost;
    private TerminalRuntime mRuntime;
    private FrameLayout mContainer;
    private final Handler bannerHandler = new Handler(Looper.getMainLooper());
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private View currentBanner;

    // ── 文件上传 ─────────────────────────────────────────────────────
    private ActivityResultLauncher<String[]> uploadLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(TerminalViewModel.class);
        // Activity Result 必须在 STARTED 之前注册（onCreate/构造期），不能在点击时才注册。
        uploadLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onUploadFileSelected);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mHost = (TerminalHost) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mHost = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mContainer = new FrameLayout(requireContext());
        mContainer.setBackgroundColor(DesignTokens.TERMINAL_BG);
        return mContainer;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Read terminal arguments and start the terminal
        Bundle args = getArguments();
        if (args != null && args.containsKey("baseUrl")) {
            TerminalViewModel.TerminalSessionArgs sessionArgs =
                new TerminalViewModel.TerminalSessionArgs(
                    args.getString("baseUrl"),
                    args.getString("cookie"),
                    args.getString("sessionId"),
                    args.getString("termTitle", "Terminal"),
                    args.getString("sessionName", ""),
                    args.getString("createdAt", ""),
                    args.getString("instanceId", ""),
                    args.getBoolean("relayDevice", false),
                    args.getString("relayDeviceId", ""),
                    args.getString("cwd", "")
                );
            mViewModel.setSessionArgs(sessionArgs);

            if (mHost != null) {
                mHost.startTerminalInFragment(sessionArgs, this);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    /**
     * Called by the Activity to set the terminal content view into this fragment.
     */
    public void setTerminalContent(View terminalRoot) {
        if (mContainer != null) {
            mContainer.removeAllViews();
            mContainer.addView(terminalRoot,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    public void setRuntime(TerminalRuntime runtime) {
        mRuntime = runtime;
    }

    public TerminalRuntime getRuntime() {
        return mRuntime;
    }

    // ── 文件上传 ─────────────────────────────────────────────────────

    /** 顶栏「更多 → 上传文件」入口（经 TerminalRuntime.ViewHost 链路回调）。 */
    public void requestFileUpload() {
        if (uploadLauncher == null) return;
        if (uploadController() == null) {
            toast("上传服务未启动，请稍后重试");
            return;
        }
        if (currentSessionId().isEmpty()) {
            toast("当前没有可上传的终端会话");
            return;
        }
        uploadLauncher.launch(new String[]{"*/*"});
    }

    private void onUploadFileSelected(@Nullable Uri uri) {
        if (uri == null) return; // 用户取消选择：不提交任务
        FileUploadController controller = uploadController();
        if (controller == null) {
            toast("上传服务未启动，请稍后重试");
            return;
        }
        String sessionId = currentSessionId();
        if (sessionId.isEmpty()) {
            toast("当前没有可上传的终端会话");
            return;
        }
        Context context = requireContext();
        UploadDocumentMetadata.Metadata meta =
            UploadDocumentMetadata.resolve(context.getContentResolver(), uri);
        // 可用时持久化读权限：进程被杀后 controller 仍可能凭 uri 重新 openInputStream。
        // 部分 provider 不授予可持久化权限（抛 SecurityException），仅降级不阻断上传：
        // 进程内该 Uri 的读权限在本次任务期间仍然有效。
        try {
            context.getContentResolver().takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        UploadTask task = controller.submit(
            currentConnectionKey(), sessionId, uri.toString(), meta.displayName, meta.size);
        if (task == null) {
            // submit 返回 null：参数非法或该 session 已有活跃上传。
            toast("已有上传任务进行中");
            return;
        }
    }

    @Nullable
    private FileUploadController uploadController() {
        return mHost == null ? null : mHost.uploadController();
    }

    /** 当前终端连接的 connectionKey：与 WebTermDeviceService 的键规则保持一致。 */
    private String currentConnectionKey() {
        if (mRuntime == null) return "";
        return UploadConnectionKeys.connectionKey(
            mRuntime.state().baseUrl(), mRuntime.state().relayDeviceId());
    }

    private String currentSessionId() {
        if (mRuntime == null) return "";
        String sessionId = mRuntime.state().sessionId();
        return sessionId == null ? "" : sessionId;
    }

    /** 浮层只渲染当前终端连接 + session 的任务；其他设备/会话的任务更新直接忽略。 */
    private boolean matchesCurrentSession(UploadTask task) {
        return task.connectionKey.equals(currentConnectionKey())
            && task.sessionId.equals(currentSessionId());
    }



    private void toast(String message) {
        Context context = getContext();
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Agent hook 横幅 ──────────────────────────────────────────────

    public void showHookNotification(JSONObject ev) {
        if (mContainer == null || ev == null) return;
        android.util.Log.d("TerminalFragment", "showHookNotification event=" + ev.toString());
        String type = ev.optString("type", "");
        if (!"notify".equals(type)) return;
        String title = ev.optString("title", "");
        String body = ev.optString("body", "");
        String level = ev.optString("level", "info");
        if (title.isEmpty() && body.isEmpty()) return;

        boolean sticky = "error".equals(level)
            || "approval_required".equals(ev.optString("agentState", ""));

        String text = title;
        if (!body.isEmpty()) {
            text = text.isEmpty() ? body : title + " · " + body;
        }

        bannerHandler.removeCallbacksAndMessages(null);
        if (currentBanner != null) {
            mContainer.removeView(currentBanner);
            currentBanner = null;
        }

        Context context = requireContext();
        TextView banner = new TextView(context);
        banner.setText(text);
        banner.setTextColor(DesignTokens.TEXT_PRIMARY);
        banner.setTextSize(12);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(
            UIUtils.dp(context, 12),
            UIUtils.dp(context, 8),
            UIUtils.dp(context, 12),
            UIUtils.dp(context, 8)
        );
        banner.setMaxLines(2);
        banner.setEllipsize(android.text.TextUtils.TruncateAt.END);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(hookBackgroundColor(level));
        banner.setBackground(bg);
        ViewCompat.setElevation(banner, UIUtils.dp(context, 4));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.TOP;
        mContainer.addView(banner, lp);
        currentBanner = banner;

        if (!sticky) {
            bannerHandler.postDelayed(() -> {
                if (currentBanner == banner && mContainer != null) {
                    mContainer.removeView(banner);
                    currentBanner = null;
                }
            }, 3000);
        }
    }

    private int hookBackgroundColor(String level) {
        switch (level) {
            case "error":
                return DesignTokens.dangerBg();
            case "warning":
                return DesignTokens.warningBg();
            case "success":
                return DesignTokens.successBg();
            default:
                return DesignTokens.accentBgStrong();
        }
    }

    @Override
    public void onDestroyView() {
        bannerHandler.removeCallbacksAndMessages(null);
        uiHandler.removeCallbacksAndMessages(null);
        if (mHost != null) {
            mHost.detachTerminalFragment(this);
        }
        mContainer = null;
        currentBanner = null;
        mRuntime = null;
        super.onDestroyView();
    }

    /**
     * Called by the Activity to get the container for installing terminal insets.
     */
    public View getTerminalContainer() {
        return mContainer;
    }
}
