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

import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.UIUtils;
import com.webterm.core.fileupload.FileUploadController;
import com.webterm.core.fileupload.UploadTask;
import com.webterm.feature.terminal.upload.UploadConnectionKeys;
import com.webterm.feature.terminal.upload.UploadDocumentMetadata;

import org.json.JSONObject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * TerminalFragment provides the container for the terminal screen.
 * Uses TerminalViewModel for terminal session state and TerminalHost for
 * delegating terminal lifecycle to the Activity.
 */
@AndroidEntryPoint
public final class TerminalFragment extends Fragment {

    private TerminalViewModel mViewModel;
    private TerminalHost mHost;
    private FrameLayout mContainer;
    private final Handler bannerHandler = new Handler(Looper.getMainLooper());
    private View currentBanner;
    private ActivityResultLauncher<String[]> uploadLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(TerminalViewModel.class);
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
                    args.getString("createdAt", ""),
                    args.getString("instanceId", ""),
                    args.getBoolean("relayDevice", false),
                    args.getString("relayDeviceId", ""),
                    args.getString("cwd", "")
                );
            mViewModel.setSessionArgs(sessionArgs);

            if (mHost != null) {
                mHost.startRemoteTerminalInFragment(sessionArgs, this);
            }
        }
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

    public void requestFileUpload() {
        if (uploadLauncher == null) return;
        if (uploadController() == null) {
            toast("上传服务未启动，请稍后重试");
            return;
        }
        TerminalViewModel.TerminalSessionArgs args = currentArgs();
        if (args == null || args.sessionId == null || args.sessionId.isEmpty()) {
            toast("当前没有可上传的终端会话");
            return;
        }
        uploadLauncher.launch(new String[]{"*/*"});
    }

    private void onUploadFileSelected(@Nullable Uri uri) {
        if (uri == null) return;
        FileUploadController controller = uploadController();
        TerminalViewModel.TerminalSessionArgs args = currentArgs();
        if (controller == null || args == null) {
            toast("上传服务未启动，请稍后重试");
            return;
        }
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        UploadDocumentMetadata.Metadata metadata = UploadDocumentMetadata.resolve(
            requireContext().getContentResolver(), uri);
        String connectionKey = UploadConnectionKeys.connectionKey(args.baseUrl, args.relayDeviceId);
        UploadTask task = controller.submit(connectionKey, args.sessionId, uri.toString(),
            metadata.displayName, metadata.size);
        if (task == null) toast("已有上传任务进行中");
    }

    @Nullable
    private TerminalViewModel.TerminalSessionArgs currentArgs() {
        return mViewModel == null ? null : mViewModel.getSessionArgs().getValue();
    }

    @Nullable
    private FileUploadController uploadController() {
        return mHost == null ? null : mHost.uploadController();
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

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
        if (mHost != null) {
            mHost.detachTerminalFragment(this);
        }
        mContainer = null;
        currentBanner = null;
        super.onDestroyView();
    }

    /**
     * Called by the Activity to get the container for installing terminal insets.
     */
    public View getTerminalContainer() {
        return mContainer;
    }
}
