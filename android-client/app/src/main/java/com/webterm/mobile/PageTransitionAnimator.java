package com.webterm.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

final class PageTransitionAnimator {

    enum Transition {
        NONE,
        FORWARD,
        BACK,
        FADE
    }

    static void animate(Activity activity, View newRoot, Transition transition) {
        if (newRoot == null) return;
        if (transition == Transition.NONE) { activity.setContentView(newRoot); return; }

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) { activity.setContentView(newRoot); return; }

        View oldRoot = content.getChildAt(content.getChildCount() - 1);
        if (oldRoot == null || oldRoot == newRoot) { activity.setContentView(newRoot); return; }
        for (int i = content.getChildCount() - 2; i >= 0; i--) {
            View stale = content.getChildAt(i);
            stale.animate().cancel();
            content.removeViewAt(i);
        }

        ViewGroup parent = (ViewGroup) newRoot.getParent();
        if (parent != null) parent.removeView(newRoot);

        oldRoot.animate().cancel();
        newRoot.animate().cancel();
        oldRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        newRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        int offset = dp(activity, 18);
        float newStartX = transition == Transition.FORWARD ? offset
            : transition == Transition.BACK ? -offset : 0f;

        newRoot.setAlpha(transition == Transition.FADE ? 0f : 1f);
        newRoot.setTranslationX(newStartX);
        content.addView(newRoot, new ViewGroup.LayoutParams(-1, -1));

        DecelerateInterpolator interpolator = new DecelerateInterpolator();
        newRoot.animate()
            .alpha(1f).translationX(0f).setDuration(180L).setInterpolator(interpolator)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    newRoot.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            }).start();

        if (transition == Transition.FADE) {
            oldRoot.animate().alpha(0f).setDuration(180L).setInterpolator(interpolator)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        removeOldRoot(content, oldRoot);
                    }
                }).start();
        } else {
            oldRoot.setLayerType(View.LAYER_TYPE_NONE, null);
            newRoot.postDelayed(() -> removeOldRoot(content, oldRoot), 190L);
        }
    }

    private static void removeOldRoot(ViewGroup content, View oldRoot) {
        if (oldRoot.getParent() == content) content.removeView(oldRoot);
        oldRoot.setAlpha(1f);
        oldRoot.setTranslationX(0f);
        oldRoot.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
