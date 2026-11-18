package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;

/** Shared button language for the main pages and the steps screens. */
final class AppButtonStyle {
    enum Kind { PRIMARY, SECONDARY, DANGER, LINK }

    private AppButtonStyle() {}

    static Button create(Context context, String text, Kind kind, View.OnClickListener click) {
        Button button = new Button(context);
        button.setText(text);
        apply(button, kind);
        if (click != null) button.setOnClickListener(click);
        return button;
    }

    static Button custom(Context context, String text, int fill, int textColor, int stroke,
                         View.OnClickListener click) {
        Button button = new Button(context);
        button.setText(text);
        base(button);
        button.setTextColor(textColor);
        button.setBackground(ripple(context, fill, stroke, dp(context, 20)));
        if (click != null) button.setOnClickListener(click);
        return button;
    }

    static void apply(Button button, Kind kind) {
        Context context = button.getContext();
        base(button);
        int fill, text, stroke;
        switch (kind) {
            case PRIMARY:
                fill = color(context, "app_accent", Color.rgb(0, 101, 184));
                text = color(context, "app_on_accent", Color.WHITE);
                stroke = fill;
                break;
            case DANGER:
                fill = color(context, "app_surface", Color.WHITE);
                text = color(context, "app_danger", Color.rgb(179, 38, 30));
                stroke = text;
                break;
            case LINK:
                fill = Color.TRANSPARENT;
                text = color(context, "app_accent", Color.rgb(0, 101, 184));
                stroke = Color.TRANSPARENT;
                button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                button.setPadding(0, dp(context, 4), 0, dp(context, 4));
                break;
            default:
                fill = color(context, "app_selected", Color.rgb(221, 238, 255));
                text = color(context, "app_accent", Color.rgb(0, 101, 184));
                stroke = Color.TRANSPARENT;
                break;
        }
        button.setTextColor(text);
        button.setBackground(ripple(context, fill, stroke, dp(context, kind == Kind.LINK ? 12 : 20)));
        setEnabled(button, button.isEnabled());
    }

    static void setEnabled(Button button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.42f);
    }

    private static void base(Button button) {
        Context context = button.getContext();
        button.setTextSize(15);
        button.setSingleLine(false);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setGravity(Gravity.CENTER);
    }

    private static Drawable ripple(Context context, int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(context, 1), stroke);
        int primary = color(context, "app_text_primary", Color.rgb(25, 30, 40));
        int pressed = Color.argb(36, Color.red(primary), Color.green(primary), Color.blue(primary));
        return new RippleDrawable(ColorStateList.valueOf(pressed), shape, null);
    }

    private static int color(Context context, String name, int fallback) {
        int id = context.getResources().getIdentifier(name, "color", context.getPackageName());
        return id == 0 ? fallback : context.getColor(id);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
