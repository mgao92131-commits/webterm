package com.webterm.mobile.ui.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.webterm.mobile.ui.MainActivity;
import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.PageTransitionAnimator;
import com.webterm.mobile.ui.common.UIUtils;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.api.WebTermUrls;

import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * HomeFragment owns the home screen (device list) and device sessions UI.
 * It switches between home and device-sessions views internally.
 */
@AndroidEntryPoint
public final class HomeFragment extends Fragment {

    private MainActivity mMainActivity;
    private FrameLayout mContainer;

    // Home screen state
    private LinearLayout mSessionList;
    private boolean mShowingDeviceSessions;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mMainActivity = (MainActivity) requireActivity();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mMainActivity = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mContainer = new FrameLayout(mMainActivity);
        return mContainer;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Check if we should restore device sessions state
        if (savedInstanceState != null) {
            mShowingDeviceSessions = savedInstanceState.getBoolean("showingDeviceSessions", false);
        }
        if (!mShowingDeviceSessions) {
            showHomeScreen();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("showingDeviceSessions", mShowingDeviceSessions);
    }

    /**
     * Show the home screen (device list).
     */
    public void showHomeScreen() {
        mShowingDeviceSessions = false;
        mContainer.removeAllViews();

        HomeScreenBuilder.HomeResult home = HomeScreenBuilder.buildHome(
            mMainActivity,
            () -> mMainActivity.showAddServerDialog(null),
            () -> mMainActivity.showSettingsDialog(),
            () -> { loadMultiSessions(); mMainActivity.getRelayService().start(); },
            () -> mMainActivity.navigateToRelay(),
            () -> mMainActivity.shareLatestCrashLog()
        );

        mMainActivity.setHomeSubtitle(home.subtitle);
        mMainActivity.getRelayUiState().attachSubtitle(home.subtitle);
        mMainActivity.getRelayUiState().attachStatusDot(home.homeStatus);
        mMainActivity.installRootInsets(home.root, 0, 0, 0, dp(16), true, true);

        mSessionList = home.sessionList;
        mMainActivity.setSessionList(mSessionList);
        mContainer.addView(home.root);
        loadMultiSessions();
        mMainActivity.getRelayService().start();
    }

    /**
     * Show device sessions for the given server.
     */
    public void showDeviceSessions(ServerConfig server) {
        if (server == null) {
            showHomeScreen();
            return;
        }
        mShowingDeviceSessions = true;
        mContainer.removeAllViews();

        mMainActivity.getRelayService().stop();

        HomeScreenBuilder.DeviceSessionsResult screen = HomeScreenBuilder.buildDeviceSessions(
            mMainActivity, server,
            () -> showHomeScreen(),
            () -> mMainActivity.getSessionCommands().createSessionOnServer(server),
            () -> loadDeviceSessions(server),
            () -> mMainActivity.showAddServerDialog(server),
            () -> mMainActivity.confirmRemoveServer(server)
        );

        mMainActivity.installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);
        SessionRecyclerAdapter adapter = new SessionRecyclerAdapter(mMainActivity, mMainActivity,
            () -> loadDeviceSessions(server));
        screen.sessionList.setAdapter(adapter);
        setupSwipeToDelete(screen.sessionList, adapter);

        if (mMainActivity.getHomeCoordinator() != null) {
            mMainActivity.getHomeCoordinator().attachSessionAdapter(adapter);
            mMainActivity.getHomeCoordinator().loadDeviceSessions(server, screen.status);
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

    private void loadMultiSessions() {
        if (mSessionList == null) return;
        List<ServerConfig> allServers = collectVisibleDevices();
        mSessionList.removeAllViews();
        if (allServers.isEmpty()) {
            mSessionList.addView(HomeScreenBuilder.emptyState(mMainActivity),
                new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (ServerConfig server : allServers) {
            mSessionList.addView(HomeScreenBuilder.deviceCard(
                mMainActivity, server,
                (v) -> showDeviceSessions(server),
                () -> mMainActivity.showAddServerDialog(server),
                () -> mMainActivity.confirmRemoveServer(server)
            ));
        }
    }

    private List<ServerConfig> collectVisibleDevices() {
        List<ServerConfig> allServers = new ArrayList<>();
        for (ServerConfig s : mMainActivity.getServerConfigs().servers()) {
            if (!s.isRelayMaster()) allServers.add(s);
        }
        List<ServerConfig> relayDevices = mMainActivity.getRelayService().devices();
        if (!relayDevices.isEmpty()) allServers.addAll(relayDevices);
        return allServers;
    }

    private void loadDeviceSessions(ServerConfig server) {
        if (mMainActivity.getHomeCoordinator() != null && server != null) {
            // Status indicator is managed by the coordinator
            mMainActivity.getHomeCoordinator().loadDeviceSessions(server, null);
        }
    }

    private void setupSwipeToDelete(RecyclerView recyclerView, SessionRecyclerAdapter adapter) {
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder vh, RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                final int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                String sessionId = adapter.getSessionId(position);
                if (sessionId != null) {
                    mMainActivity.closeSession(mMainActivity.getSelectedServer(), sessionId);
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
                    android.graphics.RectF background = new android.graphics.RectF(
                        itemView.getRight() + dX, itemView.getTop(),
                        itemView.getRight(), itemView.getBottom());
                    c.drawRect(background, paint);
                    paint.setColor(android.graphics.Color.WHITE);
                    paint.setTextSize(UIUtils.dp(mMainActivity, 14));
                    paint.setAntiAlias(true);
                    paint.setTypeface(DesignTokens.fontGeistSansSemibold(mMainActivity));
                    String text = "关闭会话";
                    float textWidth = paint.measureText(text);
                    android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                    float textHeight = fm.bottom - fm.top;
                    float cardHeight = itemView.getHeight();
                    float x = itemView.getRight() + dX / 2f - textWidth / 2f;
                    float y = itemView.getTop() + cardHeight / 2f - textHeight / 2f - fm.top;
                    if (-dX > textWidth + UIUtils.dp(mMainActivity, 24)) {
                        c.drawText(text, x, y, paint);
                    }
                }
                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView);
    }

    private int dp(int value) {
        return PageTransitionAnimator.dp(mMainActivity, value);
    }
}
