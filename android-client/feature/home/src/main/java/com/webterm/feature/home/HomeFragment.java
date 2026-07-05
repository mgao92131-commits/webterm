package com.webterm.feature.home;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.webterm.core.config.ServerConfig;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.PageTransitionAnimator;
import com.webterm.feature.relay.RelayUiState;
import com.webterm.terminal.ui.TerminalWindowInsetsController;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * HomeFragment owns the top-level home screen.
 * Uses HomeViewModel for business logic and data, HomeHost for Activity-level operations.
 */
@AndroidEntryPoint
public final class HomeFragment extends Fragment {

    private HomeViewModel mViewModel;
    private HomeHost mHost;
    private FrameLayout mContainer;

    // Home screen state
    private LinearLayout mSessionList;
    private int mImeOverlap;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(HomeViewModel.class);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mHost = (HomeHost) context;
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
        return mContainer;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Observe ViewModel data
        mViewModel.getDevices().observe(getViewLifecycleOwner(), this::onDevicesChanged);

        // Observe one-shot events
        mViewModel.getNavigateToDeviceSessions().observe(getViewLifecycleOwner(), server -> {
            if (mHost != null) mHost.navigateToDeviceSessions(server);
        });
        mViewModel.getNavigateToRelay().observe(getViewLifecycleOwner(), v -> navigateToRelay());
        mViewModel.getNavigateToHome().observe(getViewLifecycleOwner(), v -> showHomeScreen());

        showHomeScreen();
    }

    // ── Home Screen ──────────────────────────────────────────────

    public void showHomeScreen() {
        mContainer.removeAllViews();

        mViewModel.loadDevices();
        mViewModel.getRelayService().start();

        HomeScreenBuilder.HomeResult home = HomeScreenBuilder.buildHome(
            requireActivity(),
            () -> {
                if (mHost != null) mHost.showAddServerDialog(null);
            },
            () -> {
                if (mHost != null) mHost.showSettingsDialog();
            },
            () -> {
                loadMultiSessions();
                mViewModel.getRelayService().refresh();
            },
            () -> mViewModel.requestRelay(),
            () -> shareCrashLog()
        );

        // Attach relay state to subtitle/status
        RelayUiState relayUiState = new RelayUiState(mViewModel.getRelayService(), null);
        relayUiState.attachSubtitle(home.subtitle);
        relayUiState.attachStatusDot(home.homeStatus);

        installRootInsets(home.root, 0, 0, 0, dp(16), true, true);

        mSessionList = home.sessionList;
        mContainer.addView(home.root);
        loadMultiSessions();
    }

    // ── Internal ─────────────────────────────────────────────────

    private void onDevicesChanged(List<ServerConfig> allDevices) {
        if (mSessionList == null) return;
        mSessionList.removeAllViews();
        if (allDevices == null || allDevices.isEmpty()) {
            mSessionList.addView(HomeScreenBuilder.emptyState(requireActivity()),
                new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (ServerConfig server : allDevices) {
            mSessionList.addView(HomeScreenBuilder.deviceCard(
                requireActivity(), server,
                (v) -> mViewModel.selectServer(server),
                () -> {
                    if (mHost != null) mHost.showAddServerDialog(server);
                },
                () -> confirmRemoveServer(server)
            ));
        }
    }

    private void loadMultiSessions() {
        mViewModel.loadDevices();
    }

    public void refreshDevices() {
        mViewModel.loadDevices();
    }

    // ── Navigation ───────────────────────────────────────────────

    private void navigateToRelay() {
        if (mHost != null) mHost.navigateToRelay();
    }

    // ── Dialogs ──────────────────────────────────────────────────

    private void confirmRemoveServer(ServerConfig server) {
        if (server == null) return;
        new AlertDialog.Builder(requireContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (d, which) -> {
                mViewModel.removeServer(server);
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    private void shareCrashLog() {
        if (mHost != null) mHost.shareCrashLog();
    }

    // ── Insets ───────────────────────────────────────────────────

    private void installRootInsets(View root, int baseLeft, int baseTop, int baseRight,
                                   int baseBottom, boolean avoidImeWithPadding,
                                   boolean includeStatusBar) {
        TerminalWindowInsetsController.installRootInsets(requireActivity(), root,
            baseLeft, baseTop, baseRight, baseBottom, avoidImeWithPadding, includeStatusBar,
            (imeOverlap) -> mImeOverlap = imeOverlap);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private int dp(int value) {
        return PageTransitionAnimator.dp(requireActivity(), value);
    }
}
