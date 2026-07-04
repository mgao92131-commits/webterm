package com.webterm.feature.settings;

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

import dagger.hilt.android.AndroidEntryPoint;

/**
 * SettingsFragment owns the settings screen.
 * Uses SettingsViewModel for state and SettingsHost for dialog display.
 */
@AndroidEntryPoint
public final class SettingsFragment extends Fragment {

    private SettingsViewModel mViewModel;
    private SettingsHost mHost;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(SettingsViewModel.class);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mHost = (SettingsHost) context;
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
        // Observe settings dialog event
        mViewModel.getShowSettingsDialog().observe(getViewLifecycleOwner(), v -> {
            if (mHost != null) mHost.showSettingsDialog();
        });

        // Trigger settings dialog on first display
        mViewModel.requestShowSettingsDialog();

        return new FrameLayout(requireContext());
    }
}
