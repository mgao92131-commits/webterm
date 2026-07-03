package com.webterm.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

public final class StatusIndicatorView extends View {
    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CONNECTED_P2P
    }

    private GradientDrawable bg;
    private AlphaAnimation connectingAnimation;
    private Status currentStatus = Status.DISCONNECTED;

    public StatusIndicatorView(Context context) {
        super(context);
        init();
    }

    public StatusIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        setBackground(bg);

        connectingAnimation = new AlphaAnimation(1.0f, 0.2f);
        connectingAnimation.setDuration(600);
        connectingAnimation.setRepeatMode(Animation.REVERSE);
        connectingAnimation.setRepeatCount(Animation.INFINITE);

        setStatus(Status.DISCONNECTED); // Default to disconnected
    }

    public void setStatus(Status status) {
        this.currentStatus = status;
        clearAnimation();
        if (bg == null) return;
        switch (status) {
            case CONNECTED:
                bg.setColor(DesignTokens.SUCCESS);
                break;
            case CONNECTED_P2P:
                bg.setColor(DesignTokens.INFO);
                break;
            case CONNECTING:
                bg.setColor(DesignTokens.WARNING);
                startAnimation(connectingAnimation);
                break;
            case DISCONNECTED:
                bg.setColor(DesignTokens.DANGER);
                break;
        }
    }

    public Status getStatus() {
        return currentStatus;
    }
}
