package com.webterm.mobile.ui.relay;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.webterm.mobile.ui.MainActivity;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * RelayFragment owns the relay login and relay devices screens.
 * It delegates business logic to MainActivity and {@link RelayUiState}.
 */
@AndroidEntryPoint
public final class RelayFragment extends Fragment {

    private MainActivity mMainActivity;

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
        // Build the appropriate relay screen based on current state.
        // The screen type is determined by MainActivity's relay state.
        return mMainActivity.buildRelayView();
    }
}
