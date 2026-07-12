package com.webterm.terminal.interaction;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * The canonical terminal gesture recognizer. Both the local emulator and the
 * remote screen projection use this class so a gesture is interpreted once.
 */
public final class GestureAndScaleRecognizer {
  public interface Listener {
    boolean onSingleTapUp(MotionEvent event);
    boolean onDoubleTap(MotionEvent event);
    boolean onScroll(MotionEvent event, float distanceX, float distanceY);
    boolean onFling(MotionEvent event, float velocityX, float velocityY);
    boolean onScale(float focusX, float focusY, float scale);
    boolean onDown(float x, float y);
    boolean onUp(MotionEvent event);
    void onLongPress(MotionEvent event);
  }

  private final GestureDetector gestureDetector;
  private final ScaleGestureDetector scaleDetector;
  private final Listener listener;
  private boolean afterLongPress;

  public GestureAndScaleRecognizer(Context context, Listener listener) {
    this.listener = listener;
    gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
        return GestureAndScaleRecognizer.this.listener.onScroll(e2, dx, dy);
      }
      @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
        return GestureAndScaleRecognizer.this.listener.onFling(e2, vx, vy);
      }
      @Override public boolean onDown(MotionEvent event) {
        return GestureAndScaleRecognizer.this.listener.onDown(event.getX(), event.getY());
      }
      @Override public void onLongPress(MotionEvent event) {
        GestureAndScaleRecognizer.this.listener.onLongPress(event);
        afterLongPress = true;
      }
    }, null, true);
    gestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
      @Override public boolean onSingleTapConfirmed(MotionEvent event) {
        return GestureAndScaleRecognizer.this.listener.onSingleTapUp(event);
      }
      @Override public boolean onDoubleTap(MotionEvent event) {
        return GestureAndScaleRecognizer.this.listener.onDoubleTap(event);
      }
      @Override public boolean onDoubleTapEvent(MotionEvent event) { return true; }
    });
    scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
      @Override public boolean onScale(ScaleGestureDetector detector) {
        return GestureAndScaleRecognizer.this.listener.onScale(
            detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
      }
    });
    scaleDetector.setQuickScaleEnabled(false);
  }

  public void onTouchEvent(MotionEvent event) {
    gestureDetector.onTouchEvent(event);
    scaleDetector.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: afterLongPress = false; break;
      case MotionEvent.ACTION_UP:
        if (!afterLongPress) listener.onUp(event);
        break;
      default: break;
    }
  }

  public boolean isInProgress() { return scaleDetector.isInProgress(); }
}
