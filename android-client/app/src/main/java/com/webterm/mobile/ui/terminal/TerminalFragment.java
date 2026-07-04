package com.webterm.mobile.ui.terminal;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.webterm.mobile.ui.MainActivity;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * TerminalFragment provides the container for the terminal screen.
 * The terminal view is built by TerminalLifecycleController and set via
 * {@link #setTerminalContent(View)}.
 */
@AndroidEntryPoint
public final class TerminalFragment extends Fragment {

    private MainActivity mMainActivity;
    private FrameLayout mContainer;

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
        mContainer.setBackgroundColor(com.webterm.mobile.ui.common.DesignTokens.TERMINAL_BG);
        return mContainer;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Read terminal arguments and start the terminal
        Bundle args = getArguments();
        if (args != null && args.containsKey("baseUrl")) {
            String baseUrl = args.getString("baseUrl");
            String cookie = args.getString("cookie");
            String sessionId = args.getString("sessionId");
            String termTitle = args.getString("termTitle", "Terminal");
            String sessionName = args.getString("sessionName", "");
            String createdAt = args.getString("createdAt", "");
            String instanceId = args.getString("instanceId", "");
            boolean relayDevice = args.getBoolean("relayDevice", false);
            String relayDeviceId = args.getString("relayDeviceId", "");
            String cwd = args.getString("cwd", "");

            mMainActivity.startTerminalInFragment(baseUrl, cookie, sessionId, termTitle,
                sessionName, createdAt, instanceId, relayDevice, relayDeviceId, cwd, this);
        }
    }

    /**
     * Called by MainActivity to set the terminal content view into this fragment.
     */
    public void setTerminalContent(View terminalRoot) {
        if (mContainer != null) {
            mContainer.removeAllViews();
            mContainer.addView(terminalRoot,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * Called by MainActivity to get the container for installing terminal insets.
     */
    public View getTerminalContainer() {
        return mContainer;
    }
}
