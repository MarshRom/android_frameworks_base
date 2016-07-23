package com.android.keyguard;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

public class BlurTextureView extends FrameLayout {
    private View mBackview;
    private float mBlur;
    private View mForeview;
    private final DecelerateInterpolator mInterp;

    public BlurTextureView(Context context) {
        this(context, null);
    }

    public BlurTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BlurTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.mInterp = new DecelerateInterpolator(2.0f);
    }

    protected void onFinishInflate() {
        this.mBackview = findViewById(R.id.background_blur);
        this.mForeview = findViewById(R.id.foreground_blur);
    }

    public void setCustomBackground(Drawable background) {
        this.mBackview.setBackground(background);
    }

    public void setCustomForeground(Drawable foreground) {
        this.mForeview.setBackground(foreground);
    }

    public void setBlur(float blur) {
        this.mBlur = this.mInterp.getInterpolation(blur);
        if (this.mForeview.getBackground() != null) {
            this.mForeview.getBackground().setAlpha((int) (this.mBlur * 255.0f));
        }
    }
}
