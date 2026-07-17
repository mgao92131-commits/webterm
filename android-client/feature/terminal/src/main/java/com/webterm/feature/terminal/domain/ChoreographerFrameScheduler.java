package com.webterm.feature.terminal.domain;

import android.view.Choreographer;

import androidx.annotation.NonNull;

import java.util.IdentityHashMap;
import java.util.Map;

/** Production scheduler. It is deliberately VSync-driven and makes no 60Hz assumption. */
final class ChoreographerFrameScheduler implements FrameScheduler {
  private final Map<Runnable, Choreographer.FrameCallback> callbacks = new IdentityHashMap<>();

  @Override
  public void postFrame(@NonNull Runnable callback) {
    Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
      synchronized (callbacks) {
        callbacks.remove(callback);
      }
      callback.run();
    };
    synchronized (callbacks) {
      callbacks.put(callback, frameCallback);
    }
    Choreographer.getInstance().postFrameCallback(frameCallback);
  }

  @Override
  public void cancelFrame(@NonNull Runnable callback) {
    Choreographer.FrameCallback frameCallback;
    synchronized (callbacks) {
      frameCallback = callbacks.remove(callback);
    }
    if (frameCallback != null) Choreographer.getInstance().removeFrameCallback(frameCallback);
  }
}
