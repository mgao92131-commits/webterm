package com.webterm.feature.home;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.webterm.core.config.ServerConfig;
import com.webterm.terminal.ui.TerminalWindowInsetsController;
import com.webterm.ui.common.DesignTokens;
import com.webterm.ui.common.PageTransitionAnimator;
import com.webterm.ui.common.UIUtils;

import org.json.JSONException;
import org.json.JSONObject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class DeviceSessionsFragment extends Fragment implements SessionRowActions {
    public static final String ARG_SERVER_JSON = "serverJson";

    private DeviceSessionsViewModel mViewModel;
    private HomeHost mHost;
    private SessionRecyclerAdapter mSessionAdapter;
    private DeviceSessionsScreenBuilder.Result mScreen;
    private int mImeOverlap;

    public static Bundle args(ServerConfig server) {
        Bundle args = new Bundle();
        if (server != null) {
            try {
                args.putString(ARG_SERVER_JSON, server.toJSON().toString());
            } catch (JSONException ignored) {
            }
        }
        return args;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(DeviceSessionsViewModel.class);
        Bundle args = getArguments();
        mViewModel.setServer(args == null ? null : readServer(args));
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        mSessionAdapter = null;
        mScreen = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ServerConfig server = mViewModel.getServer();
        if (server == null) {
            if (mHost != null) mHost.navigateHome();
            return new View(requireContext());
        }

        if (mScreen != null) {
            View root = mScreen.root;
            if (root.getParent() instanceof ViewGroup) {
                ((ViewGroup) root.getParent()).removeView(root);
            }
            return root;
        }

        mScreen = DeviceSessionsScreenBuilder.build(
            requireActivity(),
            server,
            () -> {
                if (mHost != null) mHost.navigateHome();
            },
            () -> {
                if (mHost != null) mHost.createSession(server);
            },
            () -> mViewModel.refresh(),
            () -> {
                if (mHost != null) mHost.showAddServerDialog(server);
            },
            () -> confirmRemoveServer(server)
        );

        installRootInsets(mScreen.root, 0, 0, 0, dp(16), true, true);

        mSessionAdapter = new SessionRecyclerAdapter(requireActivity(), this,
            () -> mViewModel.refresh());
        mScreen.sessionList.setAdapter(mSessionAdapter);

        // 禁用内容更新时的默认 change 动画，防止列表因数据高频更新而在滚动时产生乱晃和闪烁
        RecyclerView.ItemAnimator animator = mScreen.sessionList.getItemAnimator();
        if (animator instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        setupSwipeToDelete(mScreen.sessionList, mSessionAdapter);
        return mScreen.root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mSessionAdapter == null || mScreen == null) return;

        mViewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
            if (state == null) return;
            render(state);
        });

        mViewModel.getAuthEvent().observe(getViewLifecycleOwner(), server -> {
            if (server != null && mHost != null) mHost.saveServers();
        });
    }

    private void render(DeviceSessionsUiState state) {
        ServerConfig server = mViewModel.getServer();
        if (server == null) return;

        mScreen.status.setStatus(toStatusIndicator(state.connectionState));

        if (state.sessions != null && state.sessions.length() > 0) {
            mSessionAdapter.submitSessions(server, state.sessions);
        } else if (state.connectionState == DeviceSessionsUiState.ConnectionState.ERROR
            || state.connectionState == DeviceSessionsUiState.ConnectionState.AUTH_REQUIRED) {
            mSessionAdapter.showError(state.errorMessage != null ? state.errorMessage : "连接失败");
        } else {
            mSessionAdapter.showEmpty();
        }
    }

    private static com.webterm.ui.common.StatusIndicatorView.Status toStatusIndicator(
            DeviceSessionsUiState.ConnectionState state) {
        if (state == null) return com.webterm.ui.common.StatusIndicatorView.Status.DISCONNECTED;
        switch (state) {
            case CONNECTING:
                return com.webterm.ui.common.StatusIndicatorView.Status.CONNECTING;
            case CONNECTED:
                return com.webterm.ui.common.StatusIndicatorView.Status.CONNECTED;
            case DISCONNECTED:
            case AUTH_REQUIRED:
            case ERROR:
            default:
                return com.webterm.ui.common.StatusIndicatorView.Status.DISCONNECTED;
        }
    }

    @Override
    public void openSession(ServerConfig server, String sessionId, String termTitle,
                            String createdAt, String instanceId, String cwd) {
        if (mHost != null) {
            mHost.showTerminal(server, sessionId, termTitle, createdAt, instanceId, cwd);
        }
    }


    @Override
    public void closeSession(ServerConfig server, String sessionId) {
        if (mHost != null) {
            mHost.closeSession(server, sessionId, () -> {
                if (mSessionAdapter != null) mSessionAdapter.removeSession(sessionId);
            });
        }
    }

    private void confirmRemoveServer(ServerConfig server) {
        if (server == null) return;
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (d, which) -> {
                if (mHost != null) {
                    mHost.removeServer(server);
                    mHost.navigateHome();
                }
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void installRootInsets(View root, int baseLeft, int baseTop, int baseRight,
                                   int baseBottom, boolean avoidImeWithPadding,
                                   boolean includeStatusBar) {
        TerminalWindowInsetsController.installRootInsets(requireActivity(), root,
            baseLeft, baseTop, baseRight, baseBottom, avoidImeWithPadding, includeStatusBar,
            (imeOverlap) -> mImeOverlap = imeOverlap);
    }

    private int dp(int value) {
        return PageTransitionAnimator.dp(requireActivity(), value);
    }

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
                if (sessionId != null) closeSession(mViewModel.getServer(), sessionId);
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

    private static ServerConfig readServer(Bundle args) {
        String serverJson = args.getString(ARG_SERVER_JSON);
        if (serverJson == null || serverJson.isEmpty()) return null;
        try {
            return ServerConfig.fromJSON(new JSONObject(serverJson));
        } catch (JSONException e) {
            return null;
        }
    }
}
