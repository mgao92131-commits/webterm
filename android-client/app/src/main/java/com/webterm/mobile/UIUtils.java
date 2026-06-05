package com.webterm.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

public final class UIUtils {

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static EditText createInput(Context context, String hint) {
        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(Color.rgb(75, 85, 99));
        editText.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(Color.rgb(13, 13, 16));
        gd.setStroke(dp(context, 1), Color.rgb(55, 65, 81));
        gd.setCornerRadius(dp(context, 6));
        editText.setBackground(gd);
        return editText;
    }

    public static LinearLayout.LayoutParams matchWrap(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(context, 52));
        lp.setMargins(0, 0, 0, dp(context, 12));
        return lp;
    }

    public static void styleDialogButton(Context context, Button button, boolean primary) {
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, 6));

        if (primary) {
            button.setTextColor(Color.rgb(15, 15, 18));
            drawable.setColor(Color.rgb(16, 185, 129));
            drawable.setStroke(dp(context, 1), Color.rgb(16, 185, 129));
        } else {
            button.setTextColor(Color.rgb(243, 244, 246));
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(dp(context, 1), Color.rgb(55, 65, 81));
        }
        button.setBackground(drawable);
    }

    public static void styleStepperButton(Context context, Button button) {
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(android.view.Gravity.CENTER);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);

        button.setTextColor(Color.rgb(243, 244, 246));
        drawable.setColor(Color.rgb(55, 65, 81));
        drawable.setStroke(dp(context, 1), Color.rgb(75, 85, 99));

        button.setBackground(drawable);
    }

    public static GradientDrawable panelBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.rgb(30, 30, 36));
        drawable.setStroke(dp(context, 1), Color.rgb(55, 65, 81));
        drawable.setCornerRadius(dp(context, 8));
        return drawable;
    }
}
