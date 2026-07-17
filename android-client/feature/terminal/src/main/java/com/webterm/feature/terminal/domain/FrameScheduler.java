package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

/** Schedules UI work for the next display frame rather than an assumed refresh interval. */
interface FrameScheduler {
  void postFrame(@NonNull Runnable callback);
  void cancelFrame(@NonNull Runnable callback);
}
