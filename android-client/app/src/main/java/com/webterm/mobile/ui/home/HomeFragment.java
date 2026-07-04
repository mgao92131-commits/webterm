package com.webterm.mobile.ui.home;

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
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.webterm.core.config.ServerConfig;
import com.webterm.mobile.R;
import com.webterm.mobile.domain.server.HomeServerCoordinator;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.PageTransitionAnimator;
import com.webterm.ui.common.UIUtils;
import com.webterm.mobile.ui.relay.RelayUiState;
import com.webterm.terminal.ui.TerminalWindowInsetsController;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * HomeFragment owns the home screen (device list) and device sessions UI.
 * Uses HomeViewModel for business logic and data, HomeHost for Activity-level operations.
 */
@AndroidEntryPoint
public final class HomeFragment extends Fragment implements SessionRowActions {

    private HomeViewModel mViewModel;
    private HomeHost mHost;
    private FrameLayout mContainer;

    // Home screen state
    private LinearLayout mSessionList;
    private boolean mShowingDeviceSessions;
    private ServerConfig mCurrentDeviceServer;

    // Session adapter
    private SessionRecyclerAdapter mSessionAdapter;
    private int mImeOverlap;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        if (savedInstanceState != null) {
            mShowingDeviceSessions = savedInstanceState.getBoolean("showingDeviceSessions", false);
        }
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
        mViewModel.getNavigateToRelay().observe(getViewLifecycleOwner(), v -> navigateToRelay());
        mViewModel.getNavigateToHome().observe(getViewLifecycleOwner(), v -> showHomeScreen());

        // Initialize ViewModel with Activity-dependent objects
        mViewModel.onActivityAttached(requireActivity(), new HomeServerCoordinator.Listener() {
            @Override
            public boolean isHomeActive() {
                return isAdded() && !isHidden() && getView() != null
                    && mShowingDeviceSessions && mSessionAdapter != null;
            }
            @Override
            public boolean isServerContextActive(ServerConfig server) {
                if (!isAdded() || server == null || mCurrentDeviceServer == null) return false;
                if (!mShowingDeviceSessions) return false;
                return isSameServer(server, mCurrentDeviceServer);
            }
            @Override
            public void onAuthenticated(ServerConfig server) {
                mViewModel.saveServers();
            }
            @Override
            public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                // handled by TerminalLifecycleController in MainActivity
            }
            @Override
            public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) {
                // handled by MainActivity
            }
            @Override
            public void onRemoveMissingCachedSessionsForServer(ServerConfig server,
                    java.util.Set<String> liveSessionIdentities) {
                // handled by MainActivity
            }
        });

        // Show initial screen
        if (!mShowingDeviceSessions) {
            showHomeScreen();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("showingDeviceSessions", mShowingDeviceSessions);
    }

    // ── Home Screen ──────────────────────────────────────────────

    public void showHomeScreen() {
        mShowingDeviceSessions = false;
        mCurrentDeviceServer = null;
        mContainer.removeAllViews();

        mViewModel.loadDevices();

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
                mViewModel.getRelayService().start();
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
        mViewModel.getRelayService().start();
    }

    // ── Device Sessions Screen ───────────────────────────────────

    public void showDeviceSessions(ServerConfig server) {
        if (server == null) {
            showHomeScreen();
            return;
        }
        mShowingDeviceSessions = true;
        mCurrentDeviceServer = server;
        mContainer.removeAllViews();

        mViewModel.getRelayService().stop();

        HomeScreenBuilder.DeviceSessionsResult screen = HomeScreenBuilder.buildDeviceSessions(
            requireActivity(), server,
            () -> showHomeScreen(),
            () -> {
                if (mViewModel.getSessionCommands() != null) {
                    mViewModel.getSessionCommands().createSessionOnServer(server);
                }
            },
            () -> loadDeviceSessions(server),
            () -> {
                if (mHost != null) mHost.showAddServerDialog(server);
            },
            () -> confirmRemoveServer(server)
        );

        installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);

        mSessionAdapter = new SessionRecyclerAdapter(requireActivity(), this,
            () -> loadDeviceSessions(server));
        screen.sessionList.setAdapter(mSessionAdapter);
        setupSwipeToDelete(screen.sessionList, mSessionAdapter);

        if (mViewModel.getHomeCoordinator() != null) {
            mViewModel.getHomeCoordinator().attachSessionAdapter(mSessionAdapter);
            mViewModel.getHomeCoordinator().loadDeviceSessions(server, screen.status);
        }

        mContainer.addView(screen.root);
    }

    public boolean isShowingDeviceSessions() {
        return mShowingDeviceSessions;
    }

    public boolean handleBackPressed() {
        if (mShowingDeviceSessions) {
            showHomeScreen();
            return true;
        }
        return false;
    }

    // ── SessionRowActions implementation ─────────────────────────

    @Override
    public void openSession(ServerConfig server, String sessionId, String termTitle,
                            String sessionName, String createdAt, String instanceId, String cwd) {
        if (mHost != null) {
            mHost.showTerminal(server, sessionId, termTitle, sessionName, createdAt, instanceId, cwd);
        }
    }

    @Override
    public void renameSession(ServerConfig server, String sessionId, String oldName) {
        if (mViewModel.getSessionCommands() != null) {
            mViewModel.getSessionCommands().showRenameDialog(server, sessionId, oldName);
        }
    }

    @Override
    public void closeSession(ServerConfig server, String sessionId) {
        if (mViewModel.getSessionCommands() != null) {
            mViewModel.getSessionCommands().showCloseConfirmDialog(server, sessionId);
        }
    }

    // ── Internal ─────────────────────────────────────────────────

    private void onDevicesChanged(List<ServerConfig> allDevices) {
        if (mShowingDeviceSessions) return;
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
                (v) -> showDeviceSessions(server),
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

    private void loadDeviceSessions(ServerConfig server) {
        if (mViewModel.getHomeCoordinator() != null && server != null) {
            mViewModel.getHomeCoordinator().loadDeviceSessions(server, null);
        }
    }

    // ── Navigation ───────────────────────────────────────────────

    private void navigateToRelay() {
        NavHostFragment.findNavController(this).navigate(R.id.relayFragment);
    }

    // ── Dialogs ──────────────────────────────────────────────────

    private void confirmRemoveServer(ServerConfig server) {
        if (server == null) return;
        new AlertDialog.Builder(requireContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (d, which) -> {
                mViewModel.removeServer(server);
                showHomeScreen();
            })
            .setNegativeButton("取消", null)
            .create().show();
    }

    private void shareCrashLog() {
        String crashLog = com.webterm.mobile.CrashReporter.readLatestCrash(requireContext());
        if (crashLog == null || crashLog.trim().isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "暂无崩溃日志",
                android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT, "WebTerm 崩溃日志");
        send.putExtra(android.content.Intent.EXTRA_TEXT, crashLog);
        startActivity(android.content.Intent.createChooser(send, "导出崩溃日志"));
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

    private static boolean isSameServer(ServerConfig a, ServerConfig b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String aId = a.getId();
        String bId = b.getId();
        if (aId != null && !aId.isEmpty() && aId.equals(bId)) return true;
        String aUrl = com.webterm.core.api.WebTermUrls.normalizeBaseUrl(a.getUrl());
        String bUrl = com.webterm.core.api.WebTermUrls.normalizeBaseUrl(b.getUrl());
        if (!aUrl.equals(bUrl)) return false;
        String aDev = a.getDeviceId() == null ? "" : a.getDeviceId();
        String bDev = b.getDeviceId() == null ? "" : b.getDeviceId();
        return aDev.equals(bDev);
    }

    private int dp(int value) {
        return PageTransitionAnimator.dp(requireActivity(), value);
    }

    // ── Swipe to delete ──────────────────────────────────────────

    private void setupSwipeToDelete(RecyclerView recyclerView, SessionRecyclerAdapter adapter) {
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder vh,
                                  RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                String sessionId = adapter.getSessionId(position);
                if (sessionId != null) {
                    closeSession(mCurrentDeviceServer, sessionId);
                }
                adapter.notifyItemChanged(position);
            }

            @Override
            public int getSwipeDirs(RecyclerView rv, RecyclerView.ViewHolder viewHolder) {
                int position = viewHolder.getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && adapter.isSessionRow(position)) {
                    return ItemTouchHelper.LEFT;
                }
                return 0;
            }

            @Override
            public void onChildDraw(android.graphics.Canvas c, RecyclerView rv,
                                    RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    View itemView = viewHolder.itemView;
                    android.graphics.Paint paint = new android.graphics.Paint();
                    paint.setColor(DesignTokens.DANGER);
                    android.graphics.RectF bg = new android.graphics.RectF(
                        itemView.getRight() + dX, itemView.getTop(),
                        itemView.getRight(), itemView.getBottom());
                    c.drawRect(bg, paint);
                    paint.setColor(android.graphics.Color.WHITE);
                    paint.setTextSize(UIUtils.dp(requireContext(), 14));
                    paint.setAntiAlias(true);
                    paint.setTypeface(DesignTokens.fontGeistSansSemibold(requireContext()));
                    String text = "关闭会话";
                    float textWidth = paint.measureText(text);
                    android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                    float textHeight = fm.bottom - fm.top;
                    float cardHeight = itemView.getHeight();
                    float x = itemView.getRight() + dX / 2f - textWidth / 2f;
                    float y = itemView.getTop() + cardHeight / 2f - textHeight / 2f - fm.top;
                    if (-dX > textWidth + UIUtils.dp(requireContext(), 24)) {
                        c.drawText(text, x, y, paint);
                    }
                }
                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView);
    }
}
