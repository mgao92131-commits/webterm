package com.webterm.feature.home;

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
import com.webterm.ui.common.WindowInsetsController;

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
    private HomeHost.RelayStatusBinding mRelayStatusBinding;

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
                if (mHost != null) mHost.showAddDirectDeviceDialog();
            },
			() -> {
                if (mHost != null) mHost.showSettingsDialog();
            },
            () -> {
                loadMultiSessions();
                mViewModel.getRelayService().refresh();
            },
            () -> mViewModel.requestRelay(),
            () -> shareCrashLog(),
            mHost != null && mHost.canShareDiagnosticLogs(),
            () -> {
                if (mHost != null) mHost.shareDiagnosticLogs();
            }
        );

        // Attach relay state to subtitle/status
        if (mRelayStatusBinding != null) mRelayStatusBinding.close();
        mRelayStatusBinding = mHost != null
            ? mHost.bindRelayStatus(mViewModel.getRelayService(), home.subtitle, home.homeStatus)
            : null;

        installRootInsets(home.root, 0, 0, 0, dp(16), true, true);

        mSessionList = home.sessionList;
        mContainer.addView(home.root);
        loadMultiSessions();
    }

    @Override
    public void onDestroyView() {
        if (mRelayStatusBinding != null) {
            mRelayStatusBinding.close();
            mRelayStatusBinding = null;
        }
        super.onDestroyView();
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
        DirectCardActions directActions = new DirectCardActions() {
            @Override public void onEditDirect(ServerConfig s) {
                if (mHost != null) mHost.editDirectDevice(s);
            }
            @Override public void onReconnectDirect(ServerConfig s) {
                if (mHost != null) mHost.reconnectDirectDevice(s);
            }
            @Override public void onDeleteDirect(ServerConfig s) {
                if (mHost != null) mHost.deleteDirectDevice(s);
            }
        };
        for (ServerConfig server : allDevices) {
			mSessionList.addView(HomeScreenBuilder.deviceCard(
				requireActivity(), server,
				(v) -> mViewModel.selectServer(server),
				directActions
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

    private void shareCrashLog() {
        if (mHost != null) mHost.shareCrashLog();
    }

    // ── Insets ───────────────────────────────────────────────────

    private void installRootInsets(View root, int baseLeft, int baseTop, int baseRight,
                                   int baseBottom, boolean avoidImeWithPadding,
                                   boolean includeStatusBar) {
        WindowInsetsController.installRootInsets(requireActivity(), root,
            baseLeft, baseTop, baseRight, baseBottom, avoidImeWithPadding, includeStatusBar,
            (imeOverlap) -> mImeOverlap = imeOverlap);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private int dp(int value) {
        return PageTransitionAnimator.dp(requireActivity(), value);
    }
}
