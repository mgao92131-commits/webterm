package com.webterm.feature.relay;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * RelayFragment owns the relay login and relay devices screens.
 * Delegates view building to its {@link RelayHost}.
 */
@AndroidEntryPoint
public final class RelayFragment extends Fragment {

    private RelayHost mHost;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mHost = (RelayHost) context;
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
        if (mHost != null) {
            return mHost.buildRelayView();
        }
        return new android.widget.FrameLayout(requireContext());
    }
}
