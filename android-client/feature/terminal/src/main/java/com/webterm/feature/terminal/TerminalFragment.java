package com.webterm.feature.terminal;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.webterm.ui.common.DesignTokens;

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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(TerminalViewModel.class);
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

    @Override
    public void onDestroyView() {
        if (mHost != null) {
            mHost.detachTerminalFragment(this);
        }
        mContainer = null;
        super.onDestroyView();
    }

    /**
     * Called by the Activity to get the container for installing terminal insets.
     */
    public View getTerminalContainer() {
        return mContainer;
    }
}
