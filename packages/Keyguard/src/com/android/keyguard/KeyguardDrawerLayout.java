package com.android.keyguard;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;
import android.os.SystemClock;
import android.provider.Settings.System;
import android.support.v4.view.AccessibilityDelegateCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.KeyEventCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewGroupCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.support.v4.widget.ViewDragHelper;
import android.support.v4.widget.ViewDragHelper.Callback;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.BaseSavedState;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;

public class KeyguardDrawerLayout extends ViewGroup {
    private static final boolean ALLOW_EDGE_LOCK = false;
    private static final boolean CHILDREN_DISALLOW_INTERCEPT = true;
    private static final int DEFAULT_SCRIM_COLOR = -1728053248;
    private static final int[] LAYOUT_ATTRS = new int[]{16842931, STATE_IDLE};
    public static final int LOCK_MODE_LOCKED_CLOSED = 1;
    public static final int LOCK_MODE_LOCKED_OPEN = 2;
    public static final int LOCK_MODE_UNLOCKED = 0;
    private static final int MIN_DRAWER_MARGIN = 64;
    private static final int MIN_FLING_VELOCITY = 400;
    private static final int PEEK_DELAY = 160;
    public static final int STATE_DRAGGING = 1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_SETTLING = 2;
    private static final String TAG = "KeyguardDrawerLayout";
    private static final float TOUCH_SLOP_SENSITIVITY = 1.0f;
    private ViewDragHelper mActiveDragger;
    private final ViewDragCallback mBottomCallback;
    private final ViewDragHelper mBottomDragger;
    private boolean mChildrenCanceledTouch;
    private View mContentView;
    private boolean mDisallowInterceptRequested;
    private DisplayMetrics mDisplayMetrics;
    private int mDrawerState;
    private boolean mFirstLayout;
    private boolean mInLayout;
    private float mInitialMotionX;
    private float mInitialMotionY;
    private final Rect mInsets;
    private final ViewDragCallback mLeftCallback;
    private final ViewDragHelper mLeftDragger;
    private DrawerListener mListener;
    private int mLockModeBottom;
    private int mLockModeLeft;
    private int mLockModeRight;
    private int mMinDrawerMargin;
    private SettingsObserver mObserver;
    private int mParallaxBy;
    private float mParallaxOffset;
    private final ViewDragCallback mRightCallback;
    private final ViewDragHelper mRightDragger;
    private int mScrimColor;
    private float mScrimOpacity;
    private Paint mScrimPaint;
    private Drawable mShadowLeft;
    private Drawable mShadowRight;
    private CharSequence mTitleBottom;
    private CharSequence mTitleLeft;
    private CharSequence mTitleRight;
    private WallpaperManager mWallpaperManager;

    class AccessibilityDelegate extends AccessibilityDelegateCompat {
        private final Rect mTmpRect = new Rect();

        AccessibilityDelegate() {
        }

        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
            AccessibilityNodeInfoCompat superNode = AccessibilityNodeInfoCompat.obtain(info);
            super.onInitializeAccessibilityNodeInfo(host, superNode);
            info.setClassName(KeyguardDrawerLayout.class.getName());
            info.setSource(host);
            ViewParent parent = ViewCompat.getParentForAccessibility(host);
            if (parent instanceof View) {
                info.setParent((View) parent);
            }
            copyNodeInfoNoChildren(info, superNode);
            superNode.recycle();
            addChildrenForAccessibility(info, (ViewGroup) host);
        }

        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName(KeyguardDrawerLayout.class.getName());
        }

        public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            if (event.getEventType() != 32) {
                return super.dispatchPopulateAccessibilityEvent(host, event);
            }
            List<CharSequence> eventText = event.getText();
            View visibleDrawer = KeyguardDrawerLayout.this.findVisibleDrawer();
            if (visibleDrawer != null) {
                CharSequence title = KeyguardDrawerLayout.this.getDrawerTitle(KeyguardDrawerLayout.this.getDrawerViewAbsoluteGravity(visibleDrawer));
                if (title != null) {
                    eventText.add(title);
                }
            }
            return KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT;
        }

        private void addChildrenForAccessibility(AccessibilityNodeInfoCompat info, ViewGroup v) {
            int childCount = v.getChildCount();
            for (int i = KeyguardDrawerLayout.STATE_IDLE; i < childCount; i += KeyguardDrawerLayout.STATE_DRAGGING) {
                View child = v.getChildAt(i);
                if (!filter(child)) {
                    switch (ViewCompat.getImportantForAccessibility(child)) {
                        case KeyguardDrawerLayout.STATE_IDLE /*0*/:
                            ViewCompat.setImportantForAccessibility(child, KeyguardDrawerLayout.STATE_DRAGGING);
                            break;
                        case KeyguardDrawerLayout.STATE_DRAGGING /*1*/:
                            break;
                        case KeyguardDrawerLayout.STATE_SETTLING /*2*/:
                            if (child instanceof ViewGroup) {
                                addChildrenForAccessibility(info, (ViewGroup) child);
                                break;
                            }
                            continue;
                        case com.cyngn.keyguard.SlidingChallengeLayout.LayoutParams.CHILD_TYPE_SCRIM /*4*/:
                            break;
                        default:
                            continue;
                    }
                    info.addChild(child);
                }
            }
        }

        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child, AccessibilityEvent event) {
            if (filter(child)) {
                return KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
            }
            return super.onRequestSendAccessibilityEvent(host, child, event);
        }

        public boolean filter(View child) {
            View openDrawer = KeyguardDrawerLayout.this.findOpenDrawer();
            return (openDrawer == null || openDrawer == child) ? KeyguardDrawerLayout.ALLOW_EDGE_LOCK : KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT;
        }

        private void copyNodeInfoNoChildren(AccessibilityNodeInfoCompat dest, AccessibilityNodeInfoCompat src) {
            Rect rect = this.mTmpRect;
            src.getBoundsInParent(rect);
            dest.setBoundsInParent(rect);
            src.getBoundsInScreen(rect);
            dest.setBoundsInScreen(rect);
            dest.setVisibleToUser(src.isVisibleToUser());
            dest.setPackageName(src.getPackageName());
            dest.setClassName(src.getClassName());
            dest.setContentDescription(src.getContentDescription());
            dest.setEnabled(src.isEnabled());
            dest.setClickable(src.isClickable());
            dest.setFocusable(src.isFocusable());
            dest.setFocused(src.isFocused());
            dest.setAccessibilityFocused(src.isAccessibilityFocused());
            dest.setSelected(src.isSelected());
            dest.setLongClickable(src.isLongClickable());
            dest.addAction(src.getActions());
        }
    }

    public interface DrawerListener {
        void onDrawerClosed(View view);

        void onDrawerOpened(View view);

        void onDrawerReleased(View view, boolean z);

        void onDrawerSlide(View view, float f);

        void onDrawerStateChanged(int i);
    }

    public static class LayoutParams extends MarginLayoutParams {
        public int gravity;
        int insetDiff;
        boolean isPeeking;
        boolean knownOpen;
        int maxHeight;
        float onScreen;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            this.gravity = KeyguardDrawerLayout.STATE_IDLE;
            TypedArray a = c.obtainStyledAttributes(attrs, KeyguardDrawerLayout.LAYOUT_ATTRS);
            this.gravity = a.getInt(KeyguardDrawerLayout.STATE_IDLE, KeyguardDrawerLayout.STATE_IDLE);
            a.recycle();
            a = c.obtainStyledAttributes(attrs, R.styleable.KeyguardDrawerLayout_Layout);
            this.maxHeight = a.getDimensionPixelSize(KeyguardDrawerLayout.STATE_IDLE, KeyguardDrawerLayout.STATE_IDLE);
            a.recycle();
        }

        public LayoutParams(int width, int height) {
            super(width, height);
            this.gravity = KeyguardDrawerLayout.STATE_IDLE;
        }

        public LayoutParams(int width, int height, int gravity) {
            this(width, height);
            this.gravity = gravity;
        }

        public LayoutParams(LayoutParams source) {
            super(source);
            this.gravity = KeyguardDrawerLayout.STATE_IDLE;
            this.gravity = source.gravity;
        }

        public LayoutParams(android.view.ViewGroup.LayoutParams source) {
            super(source);
            this.gravity = KeyguardDrawerLayout.STATE_IDLE;
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
            this.gravity = KeyguardDrawerLayout.STATE_IDLE;
        }
    }

    protected static class SavedState extends BaseSavedState {
        public static final Creator<SavedState> CREATOR = new Creator<SavedState>() {
            public SavedState createFromParcel(Parcel source) {
                return new SavedState(source);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        int lockModeBottom = KeyguardDrawerLayout.STATE_IDLE;
        int lockModeLeft = KeyguardDrawerLayout.STATE_IDLE;
        int lockModeRight = KeyguardDrawerLayout.STATE_IDLE;
        int openDrawerGravity = KeyguardDrawerLayout.STATE_IDLE;

        public SavedState(Parcel in) {
            super(in);
            this.openDrawerGravity = in.readInt();
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(this.openDrawerGravity);
        }
    }

    private class SettingsObserver extends ContentObserver {
        private boolean mObserving = KeyguardDrawerLayout.ALLOW_EDGE_LOCK;

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            this.mObserving = KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT;
            KeyguardDrawerLayout.this.getContext().getContentResolver().registerContentObserver(System.getUriFor("expanded_desktop_state"), KeyguardDrawerLayout.ALLOW_EDGE_LOCK, this);
            onChange(KeyguardDrawerLayout.ALLOW_EDGE_LOCK);
        }

        void unobserve() {
            if (this.mObserving) {
                KeyguardDrawerLayout.this.getContext().getContentResolver().unregisterContentObserver(this);
                this.mObserving = KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
            }
        }

        public void onChange(boolean selfChange) {
            KeyguardDrawerLayout.this.requestLayout();
        }
    }

    public static abstract class SimpleDrawerListener implements DrawerListener {
        public void onDrawerSlide(View drawerView, float slideOffset) {
        }

        public void onDrawerOpened(View drawerView) {
        }

        public void onDrawerClosed(View drawerView) {
        }

        public void onDrawerReleased(View drawerView, boolean isOpending) {
        }

        public void onDrawerStateChanged(int newState) {
        }
    }

    private class ViewDragCallback extends Callback {
        private final int mAbsGravity;
        private ViewDragHelper mDragger;
        private final Runnable mPeekRunnable = new Runnable() {
            public void run() {
                ViewDragCallback.this.peekDrawer();
            }
        };

        public ViewDragCallback(int gravity) {
            this.mAbsGravity = gravity;
        }

        public void setDragger(ViewDragHelper dragger) {
            this.mDragger = dragger;
        }

        public void removeCallbacks() {
            KeyguardDrawerLayout.this.removeCallbacks(this.mPeekRunnable);
        }

        public boolean tryCaptureView(View child, int pointerId) {
            return (KeyguardDrawerLayout.this.isDrawerView(child) && KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, this.mAbsGravity) && KeyguardDrawerLayout.this.getDrawerLockMode(child) == 0) ? KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT : KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
        }

        public void onViewDragStateChanged(int state) {
            KeyguardDrawerLayout.this.updateDrawerState(this.mAbsGravity, state, this.mDragger.getCapturedView());
        }

        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            float offset;
            int childWidth = changedView.getWidth();
            int childHeight = changedView.getHeight();
            if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(changedView, 3)) {
                offset = ((float) (childWidth + left)) / ((float) childWidth);
            } else if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(changedView, 5)) {
                offset = ((float) (KeyguardDrawerLayout.this.getWidth() - left)) / ((float) childWidth);
            } else {
                offset = ((float) (KeyguardDrawerLayout.this.getHeight() - top)) / ((float) childHeight);
            }
            KeyguardDrawerLayout.this.setDrawerViewOffset(changedView, offset);
            changedView.setVisibility(offset == 0.0f ? 4 : KeyguardDrawerLayout.STATE_IDLE);
            KeyguardDrawerLayout.this.invalidate();
        }

        public void onViewCaptured(View capturedChild, int activePointerId) {
            ((LayoutParams) capturedChild.getLayoutParams()).isPeeking = KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
            closeOtherDrawer();
        }

        private void closeOtherDrawer() {
            int otherGrav = 3;
            if (this.mAbsGravity == 3) {
                otherGrav = 5;
            }
            View toClose = KeyguardDrawerLayout.this.findDrawerWithGravity(otherGrav);
            if (toClose != null) {
                KeyguardDrawerLayout.this.closeDrawer(toClose);
            }
        }

        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            boolean isOpening = KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT;
            int newPosition = KeyguardDrawerLayout.STATE_IDLE;
            float offset = KeyguardDrawerLayout.this.getDrawerViewOffset(releasedChild);
            int childWidth = releasedChild.getWidth();
            int childHeight = releasedChild.getHeight();
            if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(releasedChild, 3)) {
                if (xvel <= 0.0f && (xvel != 0.0f || offset <= 0.5f)) {
                    isOpening = KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
                }
                if (!isOpening) {
                    newPosition = -childWidth;
                }
            } else if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(releasedChild, 5)) {
                int width = KeyguardDrawerLayout.this.getWidth();
                if (xvel >= 0.0f && (xvel != 0.0f || offset <= 0.5f)) {
                    isOpening = KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
                }
                if (isOpening) {
                    newPosition = width - childWidth;
                } else {
                    newPosition = width;
                }
            } else {
                int height = KeyguardDrawerLayout.this.getHeight();
                if (yvel >= 0.0f && (yvel != 0.0f || offset <= 0.5f)) {
                    isOpening = KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
                }
                if (isOpening) {
                    newPosition = height - childHeight;
                } else {
                    newPosition = height;
                }
            }
            if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(releasedChild, 3) || KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(releasedChild, 5)) {
                this.mDragger.settleCapturedViewAt(newPosition, releasedChild.getTop());
            } else {
                this.mDragger.settleCapturedViewAt(releasedChild.getLeft(), newPosition);
            }
            KeyguardDrawerLayout.this.invalidate();
            KeyguardDrawerLayout.this.dispatchOnDrawerReleased(releasedChild, isOpening);
        }

        public void onEdgeTouched(int edgeFlags, int pointerId) {
            KeyguardDrawerLayout.this.postDelayed(this.mPeekRunnable, 160);
        }

        private void peekDrawer() {
            View toCapture;
            int childLeft;
            int childTop;
            int peekDistance = this.mDragger.getEdgeSize();
            boolean leftEdge = this.mAbsGravity == 3 ? KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT : KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
            boolean rightEdge = this.mAbsGravity == 5 ? KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT : KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
            boolean bottomEdge = this.mAbsGravity == 80 ? KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT : KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
            if (KeyguardDrawerLayout.this.findDrawerWithGravity(3) != null) {
                toCapture = KeyguardDrawerLayout.this.findDrawerWithGravity(3);
                childLeft = (toCapture != null ? -toCapture.getWidth() : KeyguardDrawerLayout.STATE_IDLE) + peekDistance;
                childTop = toCapture != null ? toCapture.getTop() : KeyguardDrawerLayout.STATE_IDLE;
            } else if (KeyguardDrawerLayout.this.findDrawerWithGravity(5) != null) {
                toCapture = KeyguardDrawerLayout.this.findDrawerWithGravity(5);
                childLeft = KeyguardDrawerLayout.this.getWidth() - peekDistance;
                childTop = toCapture != null ? toCapture.getTop() : KeyguardDrawerLayout.STATE_IDLE;
            } else {
                toCapture = KeyguardDrawerLayout.this.findDrawerWithGravity(80);
                childLeft = toCapture != null ? toCapture.getLeft() : KeyguardDrawerLayout.STATE_IDLE;
                childTop = KeyguardDrawerLayout.this.getHeight() - peekDistance;
            }
            if (toCapture == null) {
                return;
            }
            if (((leftEdge && toCapture.getLeft() < childLeft) || ((rightEdge && toCapture.getLeft() > childLeft) || (bottomEdge && toCapture.getBottom() < childTop))) && KeyguardDrawerLayout.this.getDrawerLockMode(toCapture) == 0) {
                LayoutParams lp = (LayoutParams) toCapture.getLayoutParams();
                this.mDragger.smoothSlideViewTo(toCapture, childLeft, childTop);
                lp.isPeeking = KeyguardDrawerLayout.CHILDREN_DISALLOW_INTERCEPT;
                KeyguardDrawerLayout.this.invalidate();
                closeOtherDrawer();
                KeyguardDrawerLayout.this.cancelChildViewTouch();
            }
        }

        public boolean onEdgeLock(int edgeFlags) {
            return KeyguardDrawerLayout.ALLOW_EDGE_LOCK;
        }

        public void onEdgeDragStarted(int edgeFlags, int pointerId) {
            View toCapture;
            if ((edgeFlags & KeyguardDrawerLayout.STATE_DRAGGING) == KeyguardDrawerLayout.STATE_DRAGGING) {
                toCapture = KeyguardDrawerLayout.this.findDrawerWithGravity(3);
            } else if ((edgeFlags & KeyguardDrawerLayout.STATE_SETTLING) == KeyguardDrawerLayout.STATE_SETTLING) {
                toCapture = KeyguardDrawerLayout.this.findDrawerWithGravity(5);
            } else {
                toCapture = KeyguardDrawerLayout.this.findDrawerWithGravity(80);
            }
            if (toCapture != null && KeyguardDrawerLayout.this.getDrawerLockMode(toCapture) == 0) {
                this.mDragger.captureChildView(toCapture, pointerId);
            }
        }

        public int getViewHorizontalDragRange(View child) {
            if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, 3) || KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, 5)) {
                return child.getWidth();
            }
            return KeyguardDrawerLayout.STATE_IDLE;
        }

        public int getViewVerticalDragRange(View child) {
            if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, 80)) {
                return child.getHeight();
            }
            return KeyguardDrawerLayout.STATE_IDLE;
        }

        public int clampViewPositionHorizontal(View child, int left, int dx) {
            if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, 5)) {
                int width = KeyguardDrawerLayout.this.getWidth();
                return Math.max(width - child.getWidth(), Math.min(left, width));
            } else if (KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, 3)) {
                return Math.max(-child.getWidth(), Math.min(left, KeyguardDrawerLayout.STATE_IDLE));
            } else {
                return KeyguardDrawerLayout.STATE_IDLE;
            }
        }

        public int clampViewPositionVertical(View child, int top, int dy) {
            if (!KeyguardDrawerLayout.this.checkDrawerViewAbsoluteGravity(child, 80)) {
                return KeyguardDrawerLayout.STATE_IDLE;
            }
            int height = KeyguardDrawerLayout.this.getHeight();
            return Math.max(height - child.getHeight(), Math.min(top, height));
        }
    }

    public KeyguardDrawerLayout(Context context) {
        this(context, null);
    }

    public KeyguardDrawerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, STATE_IDLE);
    }

    public KeyguardDrawerLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.mScrimColor = DEFAULT_SCRIM_COLOR;
        this.mScrimPaint = new Paint();
        this.mFirstLayout = CHILDREN_DISALLOW_INTERCEPT;
        this.mInsets = new Rect();
        this.mActiveDragger = null;
        this.mWallpaperManager = WallpaperManager.getInstance(context);
        if (System.getIntForUser(context.getContentResolver(), "expanded_desktop_state", STATE_IDLE, -2) == STATE_DRAGGING) {
            this.mDisplayMetrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService("window");
            if (wm == null) {
                this.mDisplayMetrics = null;
            } else {
                wm.getDefaultDisplay().getRealMetrics(this.mDisplayMetrics);
            }
        }
        if (this.mDisplayMetrics == null) {
            this.mDisplayMetrics = getResources().getDisplayMetrics();
        }
        float density = this.mDisplayMetrics.density;
        this.mMinDrawerMargin = (int) ((64.0f * density) + 0.5f);
        float minVel = 400.0f * density;
        this.mLeftCallback = new ViewDragCallback(3);
        this.mRightCallback = new ViewDragCallback(5);
        this.mBottomCallback = new ViewDragCallback(80);
        this.mLeftDragger = ViewDragHelper.create(this, TOUCH_SLOP_SENSITIVITY, this.mLeftCallback);
        this.mLeftDragger.setEdgeTrackingEnabled(STATE_DRAGGING);
        this.mLeftDragger.setMinVelocity(minVel);
        this.mLeftCallback.setDragger(this.mLeftDragger);
        this.mRightDragger = ViewDragHelper.create(this, TOUCH_SLOP_SENSITIVITY, this.mRightCallback);
        this.mRightDragger.setEdgeTrackingEnabled(STATE_SETTLING);
        this.mRightDragger.setMinVelocity(minVel);
        this.mRightCallback.setDragger(this.mRightDragger);
        this.mBottomDragger = ViewDragHelper.create(this, TOUCH_SLOP_SENSITIVITY, this.mBottomCallback);
        this.mBottomDragger.setEdgeTrackingEnabled(8);
        this.mBottomDragger.setMinVelocity(minVel);
        this.mBottomCallback.setDragger(this.mBottomDragger);
        ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegate());
        ViewGroupCompat.setMotionEventSplittingEnabled(this, ALLOW_EDGE_LOCK);
        this.mObserver = new SettingsObserver(new Handler());
    }

    protected void onFinishInflate() {
        super.onFinishInflate();
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            if (isContentView(child)) {
                this.mContentView = child;
            }
        }
    }

    public void setInsets(Rect insets) {
        this.mInsets.set(insets);
    }

    public void setParallaxDistance(int parallaxBy) {
        this.mParallaxBy = parallaxBy;
        requestLayout();
    }

    public int getParallaxDistance() {
        return this.mParallaxBy;
    }

    public void setDrawerShadow(Drawable shadowDrawable, int gravity) {
        int absGravity = GravityCompat.getAbsoluteGravity(gravity, ViewCompat.getLayoutDirection(this));
        if ((absGravity & 3) == 3) {
            this.mShadowLeft = shadowDrawable;
            invalidate();
        }
        if ((absGravity & 5) == 5) {
            this.mShadowRight = shadowDrawable;
            invalidate();
        }
    }

    public void setDrawerShadow(int resId, int gravity) {
        setDrawerShadow(getResources().getDrawable(resId), gravity);
    }

    public void setScrimColor(int color) {
        this.mScrimColor = color;
        invalidate();
    }

    public void setDrawerListener(DrawerListener listener) {
        this.mListener = listener;
    }

    public void setDrawerLockMode(int lockMode) {
        setDrawerLockMode(lockMode, 3);
        setDrawerLockMode(lockMode, 5);
        setDrawerLockMode(lockMode, 80);
    }

    public void setDrawerLockMode(int lockMode, int edgeGravity) {
        int absGravity = GravityCompat.getAbsoluteGravity(edgeGravity, ViewCompat.getLayoutDirection(this));
        if (absGravity == 3) {
            this.mLockModeLeft = lockMode;
        } else if (absGravity == 5) {
            this.mLockModeRight = lockMode;
        } else if (absGravity == 80) {
            this.mLockModeBottom = lockMode;
        }
        if (lockMode != 0) {
            ViewDragHelper helper = null;
            switch (absGravity) {
                case SlidingChallengeLayout.SCROLL_STATE_FADING /*3*/:
                    helper = this.mLeftDragger;
                    break;
                case com.cyngn.keyguard.SlidingChallengeLayout.LayoutParams.CHILD_TYPE_WIDGETS /*5*/:
                    helper = this.mRightDragger;
                    break;
                case 80:
                    helper = this.mBottomDragger;
                    break;
            }
            if (helper != null) {
                helper.cancel();
            }
        }
        switch (lockMode) {
            case STATE_DRAGGING /*1*/:
                View toClose = findDrawerWithGravity(absGravity);
                if (toClose != null) {
                    closeDrawer(toClose);
                    return;
                }
                return;
            case STATE_SETTLING /*2*/:
                View toOpen = findDrawerWithGravity(absGravity);
                if (toOpen != null) {
                    openDrawer(toOpen);
                    return;
                }
                return;
            default:
                return;
        }
    }

    public void setDrawerLockMode(int lockMode, View drawerView) {
        if (isDrawerView(drawerView)) {
            setDrawerLockMode(lockMode, ((LayoutParams) drawerView.getLayoutParams()).gravity);
            return;
        }
        throw new IllegalArgumentException("View " + drawerView + " is not a " + "drawer with appropriate layout_gravity");
    }

    public int getDrawerLockMode(int edgeGravity) {
        int absGravity = GravityCompat.getAbsoluteGravity(edgeGravity, ViewCompat.getLayoutDirection(this));
        if (absGravity == 3) {
            return this.mLockModeLeft;
        }
        if (absGravity == 5) {
            return this.mLockModeRight;
        }
        if (absGravity == 80) {
            return this.mLockModeBottom;
        }
        return STATE_IDLE;
    }

    public int getDrawerLockMode(View drawerView) {
        int absGravity = getDrawerViewAbsoluteGravity(drawerView);
        if (absGravity == 3) {
            return this.mLockModeLeft;
        }
        if (absGravity == 5) {
            return this.mLockModeRight;
        }
        if (absGravity == 80) {
            return this.mLockModeBottom;
        }
        return STATE_IDLE;
    }

    public void setDrawerTitle(int edgeGravity, CharSequence title) {
        int absGravity = GravityCompat.getAbsoluteGravity(edgeGravity, ViewCompat.getLayoutDirection(this));
        if (absGravity == 3) {
            this.mTitleLeft = title;
        } else if (absGravity == 5) {
            this.mTitleRight = title;
        } else if (absGravity == 80) {
            this.mTitleBottom = title;
        }
    }

    public CharSequence getDrawerTitle(int edgeGravity) {
        int absGravity = GravityCompat.getAbsoluteGravity(edgeGravity, ViewCompat.getLayoutDirection(this));
        if (absGravity == 3) {
            return this.mTitleLeft;
        }
        if (absGravity == 5) {
            return this.mTitleRight;
        }
        if (absGravity == 80) {
            return this.mTitleBottom;
        }
        return null;
    }

    void updateDrawerState(int forGravity, int activeState, View activeDrawer) {
        int state;
        int leftState = this.mLeftDragger.getViewDragState();
        int rightState = this.mRightDragger.getViewDragState();
        int bottomState = this.mBottomDragger.getViewDragState();
        if (leftState == STATE_DRAGGING || rightState == STATE_DRAGGING || bottomState == STATE_DRAGGING) {
            state = STATE_DRAGGING;
        } else if (leftState == STATE_SETTLING || rightState == STATE_SETTLING || bottomState == STATE_SETTLING) {
            state = STATE_SETTLING;
        } else {
            state = STATE_IDLE;
        }
        if (activeDrawer != null && activeState == 0) {
            LayoutParams lp = (LayoutParams) activeDrawer.getLayoutParams();
            if (lp.onScreen == 0.0f) {
                dispatchOnDrawerClosed(activeDrawer);
            } else if (lp.onScreen == TOUCH_SLOP_SENSITIVITY) {
                dispatchOnDrawerOpened(activeDrawer);
            }
        }
        if (state != this.mDrawerState) {
            this.mDrawerState = state;
            if (this.mListener != null) {
                this.mListener.onDrawerStateChanged(state);
            }
        }
    }

    void dispatchOnDrawerClosed(View drawerView) {
        LayoutParams lp = (LayoutParams) drawerView.getLayoutParams();
        if (lp.knownOpen) {
            lp.knownOpen = ALLOW_EDGE_LOCK;
            if (this.mListener != null) {
                this.mListener.onDrawerClosed(drawerView);
            }
            if (hasWindowFocus()) {
                View rootView = getRootView();
                if (rootView != null) {
                    rootView.sendAccessibilityEvent(32);
                }
            }
        }
    }

    void dispatchOnDrawerOpened(View drawerView) {
        LayoutParams lp = (LayoutParams) drawerView.getLayoutParams();
        if (!lp.knownOpen) {
            lp.knownOpen = CHILDREN_DISALLOW_INTERCEPT;
            if (this.mListener != null) {
                this.mListener.onDrawerOpened(drawerView);
            }
            sendAccessibilityEvent(32);
        }
    }

    void dispatchOnDrawerReleased(View drawerView, boolean isOpening) {
        if (this.mListener != null) {
            this.mListener.onDrawerReleased(drawerView, isOpening);
        }
    }

    void dispatchOnDrawerSlide(View drawerView, float slideOffset) {
        if (this.mListener != null) {
            this.mListener.onDrawerSlide(drawerView, slideOffset);
            if (getDrawerViewAbsoluteGravity(drawerView) == 80) {
                parallaxContentViewFromBottom(slideOffset);
            } else {
                translateOtherViews(drawerView, slideOffset);
            }
        }
    }

    void setDrawerViewOffset(View drawerView, float slideOffset) {
        LayoutParams lp = (LayoutParams) drawerView.getLayoutParams();
        if (slideOffset != lp.onScreen) {
            lp.onScreen = slideOffset;
            dispatchOnDrawerSlide(drawerView, slideOffset);
        }
    }

    public float getDrawerViewOffset(int gravity) {
        View drawerView = findDrawerWithGravity(gravity);
        if (drawerView == null) {
            return 0.0f;
        }
        return getDrawerViewOffset(drawerView);
    }

    public float getDrawerViewOffset(View drawerView) {
        return ((LayoutParams) drawerView.getLayoutParams()).onScreen;
    }

    int getDrawerViewAbsoluteGravity(View drawerView) {
        return GravityCompat.getAbsoluteGravity(((LayoutParams) drawerView.getLayoutParams()).gravity, ViewCompat.getLayoutDirection(this));
    }

    boolean checkDrawerViewAbsoluteGravity(View drawerView, int checkFor) {
        return (getDrawerViewAbsoluteGravity(drawerView) & checkFor) == checkFor ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    View findOpenDrawer() {
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            if (((LayoutParams) child.getLayoutParams()).knownOpen) {
                return child;
            }
        }
        return null;
    }

    void moveDrawerToOffset(View drawerView, float slideOffset) {
        float oldOffset = getDrawerViewOffset(drawerView);
        int gravity = 80;
        if (checkDrawerViewAbsoluteGravity(drawerView, 3)) {
            gravity = 3;
        } else if (checkDrawerViewAbsoluteGravity(drawerView, 5)) {
            gravity = 5;
        }
        if (gravity == 3 || gravity == 5) {
            int width = drawerView.getWidth();
            int dx = ((int) (((float) width) * slideOffset)) - ((int) (((float) width) * oldOffset));
            if (gravity != 3) {
                dx = -dx;
            }
            drawerView.offsetLeftAndRight(dx);
        } else {
            int height = drawerView.getHeight();
            drawerView.offsetTopAndBottom(-(((int) (((float) height) * slideOffset)) - ((int) (((float) height) * oldOffset))));
        }
        setDrawerViewOffset(drawerView, slideOffset);
    }

    View findDrawerWithGravity(int gravity) {
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            if (getDrawerViewAbsoluteGravity(child) == gravity) {
                return child;
            }
        }
        return null;
    }

    static String gravityToString(int gravity) {
        if ((gravity & 3) == 3) {
            return "LEFT";
        }
        if ((gravity & 5) == 5) {
            return "RIGHT";
        }
        if ((gravity & 80) == 80) {
            return "BOTTOM";
        }
        return Integer.toHexString(gravity);
    }

    private void updateWallpaperParallax() {
        if (this.mWallpaperManager != null) {
            this.mWallpaperManager.setWallpaperOverscroll(STATE_IDLE, this.mParallaxBy);
            if (getWindowToken() != null) {
                this.mWallpaperManager.setWallpaperOverscrollOffsets(getWindowToken(), -1.0f, this.mParallaxOffset);
            }
        }
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.mObserver.unobserve();
        this.mFirstLayout = CHILDREN_DISALLOW_INTERCEPT;
        this.mParallaxBy = STATE_IDLE;
        this.mParallaxOffset = 0.0f;
        updateWallpaperParallax();
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.mFirstLayout = CHILDREN_DISALLOW_INTERCEPT;
        this.mParallaxBy = KeyguardViewManager.WALLPAPER_PAPER_OFFSET;
        this.mObserver.observe();
        updateWallpaperParallax();
    }

    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        if (!(widthMode == 1073741824 && heightMode == 1073741824)) {
            if (isInEditMode()) {
                if (widthMode != Integer.MIN_VALUE) {
                    if (widthMode == 0) {
                        widthSize = 300;
                    }
                }
                if (heightMode != Integer.MIN_VALUE) {
                    if (heightMode == 0) {
                        heightSize = 300;
                    }
                }
            } else {
                throw new IllegalArgumentException("KeyguardDrawerLayout must be measured with MeasureSpec.EXACTLY.");
            }
        }
        setMeasuredDimension(widthSize, heightSize);
        int insetHeight = (heightSize - this.mInsets.top) - this.mInsets.bottom;
        int insetHeightSpec = MeasureSpec.makeMeasureSpec(insetHeight, 1073741824);
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            if (child.getVisibility() != 8) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (isContentView(child)) {
                    child.measure(MeasureSpec.makeMeasureSpec((widthSize - lp.leftMargin) - lp.rightMargin, 1073741824), MeasureSpec.makeMeasureSpec((heightSize - lp.topMargin) - lp.bottomMargin, 1073741824));
                } else if (isDrawerView(child)) {
                    int gravity = getDrawerViewAbsoluteGravity(child);
                    if (gravity == 3 || gravity == 5) {
                        child.measure(getChildMeasureSpec(widthMeasureSpec, (this.mMinDrawerMargin + lp.leftMargin) + lp.rightMargin, lp.width), getChildMeasureSpec(heightMeasureSpec, lp.topMargin + lp.bottomMargin, lp.height));
                    } else {
                        int challengeHeightSpec = insetHeightSpec;
                        View root = getRootView();
                        if (root != null) {
                            lp.insetDiff = ((this.mDisplayMetrics.heightPixels - root.getPaddingTop()) - this.mInsets.top) - insetHeight;
                            int maxChallengeHeight = lp.maxHeight - lp.insetDiff;
                            if (maxChallengeHeight > 0) {
                                challengeHeightSpec = makeChildMeasureSpec(maxChallengeHeight, lp.height);
                            }
                        }
                        child.measure(getChildMeasureSpec(widthMeasureSpec, lp.leftMargin + lp.rightMargin, lp.width), challengeHeightSpec);
                    }
                } else {
                    throw new IllegalStateException("Child " + child + " at index " + i + " does not have a valid layout_gravity - must be Gravity.LEFT, " + "Gravity.RIGHT or Gravity.NO_GRAVITY");
                }
            }
        }
    }

    private int makeChildMeasureSpec(int maxSize, int childDimen) {
        int mode;
        int size;
        switch (childDimen) {
            case -2:
                mode = Integer.MIN_VALUE;
                size = maxSize;
                break;
            case KeyguardViewDragHelper.INVALID_POINTER /*-1*/:
                mode = 1073741824;
                size = maxSize;
                break;
            default:
                mode = 1073741824;
                size = Math.min(maxSize, childDimen);
                break;
        }
        return MeasureSpec.makeMeasureSpec(size, mode);
    }

    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        this.mInLayout = CHILDREN_DISALLOW_INTERCEPT;
        int width = r - l;
        int height = b - t;
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            if (child.getVisibility() != 8) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (isContentView(child)) {
                    child.layout(lp.leftMargin, lp.topMargin - ((int) (this.mParallaxOffset * ((float) this.mParallaxBy))), lp.leftMargin + child.getMeasuredWidth(), (lp.topMargin + child.getMeasuredHeight()) - ((int) (this.mParallaxOffset * ((float) this.mParallaxBy))));
                    ((BlurTextureView) this.mContentView).setBlur(TOUCH_SLOP_SENSITIVITY);
                    updateWallpaperParallax();
                } else {
                    int childLeft;
                    float newOffset;
                    int childWidth = child.getMeasuredWidth();
                    int childHeight = child.getMeasuredHeight();
                    if (checkDrawerViewAbsoluteGravity(child, 3)) {
                        childLeft = (-childWidth) + ((int) (((float) childWidth) * lp.onScreen));
                        newOffset = ((float) (childWidth + childLeft)) / ((float) childWidth);
                    } else if (checkDrawerViewAbsoluteGravity(child, 5)) {
                        childLeft = width - ((int) (((float) childWidth) * lp.onScreen));
                        newOffset = ((float) (width - childLeft)) / ((float) childWidth);
                    } else {
                        childLeft = width - childWidth;
                        newOffset = ((float) (height - (height - ((int) (((float) childHeight) * lp.onScreen))))) / ((float) childHeight);
                    }
                    boolean changeOffset = newOffset != lp.onScreen ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
                    switch (lp.gravity & 112) {
                        case 16:
                            int bheight = b - t;
                            int bchildTop = (height - childHeight) / STATE_SETTLING;
                            if (bchildTop < lp.topMargin) {
                                bchildTop = lp.topMargin;
                            } else {
                                if (bchildTop + childHeight > height - lp.bottomMargin) {
                                    bchildTop = (bheight - lp.bottomMargin) - childHeight;
                                }
                            }
                            child.layout(childLeft, bchildTop, childLeft + childWidth, bchildTop + childHeight);
                            break;
                        case 80:
                            int layoutBottom = (height - lp.bottomMargin) - lp.insetDiff;
                            child.layout(childLeft, layoutBottom - childHeight, childLeft + childWidth, layoutBottom);
                            break;
                        default:
                            child.layout(childLeft, lp.topMargin, childLeft + childWidth, lp.topMargin + childHeight);
                            break;
                    }
                    if (changeOffset) {
                        setDrawerViewOffset(child, newOffset);
                    }
                    int newVisibility = lp.onScreen > 0.0f ? STATE_IDLE : 4;
                    if (child.getVisibility() != newVisibility) {
                        child.setVisibility(newVisibility);
                    }
                }
            }
        }
        this.mInLayout = ALLOW_EDGE_LOCK;
        this.mFirstLayout = ALLOW_EDGE_LOCK;
    }

    public void requestLayout() {
        if (!this.mInLayout) {
            super.requestLayout();
        }
    }

    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        View challengeView = findDrawerWithGravity(80);
        if (challengeView == null || !challengeView.requestFocus(direction, previouslyFocusedRect)) {
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
        }
        return CHILDREN_DISALLOW_INTERCEPT;
    }

    private void parallaxContentViewFromBottom(float slideOffset) {
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View v = getChildAt(i);
            if (isContentView(v)) {
                int oldOffset = (int) ((TOUCH_SLOP_SENSITIVITY - this.mParallaxOffset) * ((float) this.mParallaxBy));
                this.mParallaxOffset = slideOffset;
                v.offsetTopAndBottom(-(oldOffset - ((int) ((TOUCH_SLOP_SENSITIVITY - slideOffset) * ((float) this.mParallaxBy)))));
            }
        }
        this.mParallaxOffset = slideOffset;
        updateWallpaperParallax();
        ((BlurTextureView) this.mContentView).setBlur(slideOffset);
    }

    private void translateOtherViews(View drawer, float slideOffset) {
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View v = getChildAt(i);
            if (drawer != v) {
                v.setTranslationX((float) ((3 == getDrawerViewAbsoluteGravity(drawer) ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK ? STATE_DRAGGING : -1) * ((int) (((float) drawer.getWidth()) * slideOffset))));
            }
        }
    }

    public void computeScroll() {
        int childCount = getChildCount();
        float scrimOpacity = 0.0f;
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            scrimOpacity = Math.max(scrimOpacity, ((LayoutParams) getChildAt(i).getLayoutParams()).onScreen);
        }
        this.mScrimOpacity = scrimOpacity;
        if (((this.mLeftDragger.continueSettling(CHILDREN_DISALLOW_INTERCEPT) | this.mRightDragger.continueSettling(CHILDREN_DISALLOW_INTERCEPT)) | this.mBottomDragger.continueSettling(CHILDREN_DISALLOW_INTERCEPT)) != 0) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    private static boolean hasOpaqueBackground(View v) {
        Drawable bg = v.getBackground();
        if (bg == null || bg.getOpacity() != -1) {
            return ALLOW_EDGE_LOCK;
        }
        return CHILDREN_DISALLOW_INTERCEPT;
    }

    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean drawingContent = isContentView(child);
        int clipLeft = STATE_IDLE;
        int clipRight = getWidth();
        int clipBottom = getHeight();
        int restoreCount = canvas.save();
        if (drawingContent) {
            int childCount = getChildCount();
            for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
                View v = getChildAt(i);
                if (v != child && v.getVisibility() == 0 && hasOpaqueBackground(v) && isDrawerView(v)) {
                    if (checkDrawerViewAbsoluteGravity(v, 3)) {
                        int vright = v.getRight();
                        if (vright > clipLeft) {
                            clipLeft = vright;
                        }
                    } else if (checkDrawerViewAbsoluteGravity(v, 5)) {
                        int vleft = v.getLeft();
                        if (vleft < clipRight) {
                            clipRight = vleft;
                        }
                    } else {
                        int vtop = v.getTop();
                        if (vtop < clipBottom) {
                            clipBottom = vtop;
                        }
                    }
                }
            }
            canvas.clipRect(clipLeft, STATE_IDLE, clipRight, clipBottom);
        }
        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(restoreCount);
        if (this.mScrimOpacity > 0.0f && drawingContent) {
            this.mScrimPaint.setColor((((int) (((float) ((this.mScrimColor & -16777216) >>> 24)) * this.mScrimOpacity)) << 24) | (this.mScrimColor & 16777215));
            canvas.drawRect((float) clipLeft, 0.0f, (float) clipRight, (float) clipBottom, this.mScrimPaint);
        } else if (this.mShadowLeft != null && checkDrawerViewAbsoluteGravity(child, 3)) {
            shadowWidth = this.mShadowLeft.getIntrinsicWidth();
            int childRight = child.getRight();
            alpha = Math.max(0.0f, Math.min(((float) childRight) / ((float) this.mLeftDragger.getEdgeSize()), TOUCH_SLOP_SENSITIVITY));
            this.mShadowLeft.setBounds(childRight, child.getTop(), childRight + shadowWidth, child.getBottom());
            this.mShadowLeft.setAlpha((int) (255.0f * alpha));
            this.mShadowLeft.draw(canvas);
        } else if (this.mShadowRight != null && checkDrawerViewAbsoluteGravity(child, 5)) {
            shadowWidth = this.mShadowRight.getIntrinsicWidth();
            int childLeft = child.getLeft();
            alpha = Math.max(0.0f, Math.min(((float) (getWidth() - childLeft)) / ((float) this.mRightDragger.getEdgeSize()), TOUCH_SLOP_SENSITIVITY));
            this.mShadowRight.setBounds(childLeft - shadowWidth, child.getTop(), childLeft, child.getBottom());
            this.mShadowRight.setAlpha((int) (255.0f * alpha));
            this.mShadowRight.draw(canvas);
        }
        return result;
    }

    boolean isContentView(View child) {
        return ((LayoutParams) child.getLayoutParams()).gravity == 0 ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    boolean isDrawerView(View child) {
        return (GravityCompat.getAbsoluteGravity(((LayoutParams) child.getLayoutParams()).gravity, ViewCompat.getLayoutDirection(child)) & 87) != 0 ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = MotionEventCompat.getActionMasked(ev);
        boolean interceptForDrag = (this.mLeftDragger.shouldInterceptTouchEvent(ev) | this.mRightDragger.shouldInterceptTouchEvent(ev)) | this.mBottomDragger.shouldInterceptTouchEvent(ev);
        if (!interceptForDrag) {
            return super.onTouchEvent(ev);
        }
        boolean interceptForTap = ALLOW_EDGE_LOCK;
        switch (action) {
            case STATE_IDLE /*0*/:
                float x = ev.getX();
                float y = ev.getY();
                this.mInitialMotionX = x;
                this.mInitialMotionY = y;
                if (this.mScrimOpacity > 0.0f && isContentView(this.mLeftDragger.findTopChildUnder((int) x, (int) y))) {
                    interceptForTap = CHILDREN_DISALLOW_INTERCEPT;
                }
                this.mDisallowInterceptRequested = ALLOW_EDGE_LOCK;
                this.mChildrenCanceledTouch = ALLOW_EDGE_LOCK;
                break;
            case STATE_DRAGGING /*1*/:
            case SlidingChallengeLayout.SCROLL_STATE_FADING /*3*/:
                closeDrawers(CHILDREN_DISALLOW_INTERCEPT);
                this.mDisallowInterceptRequested = ALLOW_EDGE_LOCK;
                this.mChildrenCanceledTouch = ALLOW_EDGE_LOCK;
                break;
            case STATE_SETTLING /*2*/:
                if (this.mLeftDragger.checkTouchSlop(3)) {
                    this.mLeftCallback.removeCallbacks();
                    this.mRightCallback.removeCallbacks();
                    this.mBottomCallback.removeCallbacks();
                    break;
                }
                break;
        }
        return (interceptForDrag || interceptForTap || this.mChildrenCanceledTouch) ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (this.mActiveDragger == null) {
            if (this.mLeftDragger.shouldInterceptTouchEvent(ev)) {
                this.mActiveDragger = this.mLeftDragger;
            } else if (this.mRightDragger.shouldInterceptTouchEvent(ev)) {
                this.mActiveDragger = this.mRightDragger;
            } else if (this.mBottomDragger.shouldInterceptTouchEvent(ev)) {
                this.mActiveDragger = this.mBottomDragger;
            }
        }
        if (this.mActiveDragger != null) {
            closeDrawers(CHILDREN_DISALLOW_INTERCEPT);
            this.mActiveDragger.processTouchEvent(ev);
        }
        float x;
        float y;
        switch (ev.getAction() & 255) {
            case STATE_IDLE /*0*/:
                x = ev.getX();
                y = ev.getY();
                this.mInitialMotionX = x;
                this.mInitialMotionY = y;
                this.mDisallowInterceptRequested = ALLOW_EDGE_LOCK;
                this.mChildrenCanceledTouch = ALLOW_EDGE_LOCK;
                break;
            case STATE_DRAGGING /*1*/:
                x = ev.getX();
                y = ev.getY();
                View touchedView = this.mLeftDragger.findTopChildUnder((int) x, (int) y);
                if (touchedView != null && isContentView(touchedView)) {
                    float dx = x - this.mInitialMotionX;
                    float dy = y - this.mInitialMotionY;
                    int slop = this.mLeftDragger.getTouchSlop();
                    if ((dx * dx) + (dy * dy) < ((float) (slop * slop))) {
                        View openDrawer = findOpenDrawer();
                        if (openDrawer != null) {
                            if (getDrawerLockMode(openDrawer) == STATE_SETTLING) {
                            }
                        }
                    }
                }
                closeDrawers(CHILDREN_DISALLOW_INTERCEPT);
                this.mDisallowInterceptRequested = ALLOW_EDGE_LOCK;
                this.mActiveDragger = null;
                break;
            case SlidingChallengeLayout.SCROLL_STATE_FADING /*3*/:
                closeDrawers(CHILDREN_DISALLOW_INTERCEPT);
                this.mDisallowInterceptRequested = ALLOW_EDGE_LOCK;
                this.mChildrenCanceledTouch = ALLOW_EDGE_LOCK;
                this.mActiveDragger = null;
                break;
        }
        return CHILDREN_DISALLOW_INTERCEPT;
    }

    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        this.mDisallowInterceptRequested = disallowIntercept;
        if (disallowIntercept) {
            closeDrawers(CHILDREN_DISALLOW_INTERCEPT);
        }
    }

    public void closeDrawers() {
        closeDrawers(ALLOW_EDGE_LOCK);
    }

    void closeDrawers(boolean peekingOnly) {
        boolean needsInvalidate = ALLOW_EDGE_LOCK;
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (isDrawerView(child) && (!peekingOnly || lp.isPeeking)) {
                int childWidth = child.getWidth();
                if (checkDrawerViewAbsoluteGravity(child, 3)) {
                    needsInvalidate |= this.mLeftDragger.smoothSlideViewTo(child, -childWidth, child.getTop());
                } else if (checkDrawerViewAbsoluteGravity(child, 5)) {
                    needsInvalidate |= this.mRightDragger.smoothSlideViewTo(child, getWidth(), child.getTop());
                } else {
                    needsInvalidate |= this.mBottomDragger.smoothSlideViewTo(child, child.getLeft(), getHeight());
                }
                lp.isPeeking = ALLOW_EDGE_LOCK;
            }
        }
        this.mLeftCallback.removeCallbacks();
        this.mRightCallback.removeCallbacks();
        this.mBottomCallback.removeCallbacks();
        if (needsInvalidate) {
            invalidate();
        }
    }

    public void openDrawerTo(View drawerView, float slideOffset) {
        if (isDrawerView(drawerView)) {
            if (this.mFirstLayout) {
                LayoutParams lp = (LayoutParams) drawerView.getLayoutParams();
                lp.onScreen = slideOffset;
                if (checkDrawerViewAbsoluteGravity(drawerView, 80)) {
                    this.mParallaxOffset = lp.onScreen;
                    updateWallpaperParallax();
                }
            } else if (checkDrawerViewAbsoluteGravity(drawerView, 3)) {
                this.mLeftDragger.smoothSlideViewTo(drawerView, -(drawerView.getWidth() - ((int) (((float) drawerView.getWidth()) * slideOffset))), drawerView.getTop());
            } else if (checkDrawerViewAbsoluteGravity(drawerView, 5)) {
                this.mRightDragger.smoothSlideViewTo(drawerView, getWidth() - ((int) (((float) drawerView.getWidth()) * slideOffset)), drawerView.getTop());
            } else {
                this.mBottomDragger.smoothSlideViewTo(drawerView, STATE_IDLE, getHeight() - ((int) (((float) drawerView.getHeight()) * slideOffset)));
            }
            invalidate();
            return;
        }
        throw new IllegalArgumentException("View " + drawerView + " is not a sliding drawer");
    }

    public void openDrawerTo(int gravity, float slideOffset) {
        View drawerView = findDrawerWithGravity(gravity);
        if (drawerView == null) {
            throw new IllegalArgumentException("No drawer view found with gravity " + gravityToString(gravity));
        }
        openDrawerTo(drawerView, slideOffset);
    }

    public void openDrawer(View drawerView) {
        if (isDrawerView(drawerView)) {
            if (this.mFirstLayout) {
                LayoutParams lp = (LayoutParams) drawerView.getLayoutParams();
                lp.onScreen = TOUCH_SLOP_SENSITIVITY;
                lp.knownOpen = CHILDREN_DISALLOW_INTERCEPT;
                if (checkDrawerViewAbsoluteGravity(drawerView, 80)) {
                    this.mParallaxOffset = lp.onScreen;
                    updateWallpaperParallax();
                }
            } else {
                if (checkDrawerViewAbsoluteGravity(drawerView, 3)) {
                    this.mLeftDragger.smoothSlideViewTo(drawerView, STATE_IDLE, drawerView.getTop());
                }
                if (checkDrawerViewAbsoluteGravity(drawerView, 5)) {
                    this.mRightDragger.smoothSlideViewTo(drawerView, getWidth() - drawerView.getWidth(), drawerView.getTop());
                } else {
                    this.mBottomDragger.smoothSlideViewTo(drawerView, STATE_IDLE, getHeight() - drawerView.getHeight());
                }
            }
            invalidate();
            return;
        }
        throw new IllegalArgumentException("View " + drawerView + " is not a sliding drawer");
    }

    public void openDrawer(int gravity) {
        View drawerView = findDrawerWithGravity(gravity);
        if (drawerView == null) {
            throw new IllegalArgumentException("No drawer view found with gravity " + gravityToString(gravity));
        }
        openDrawer(drawerView);
    }

    public void closeDrawer(View drawerView) {
        if (isDrawerView(drawerView)) {
            if (this.mFirstLayout) {
                LayoutParams lp = (LayoutParams) drawerView.getLayoutParams();
                lp.onScreen = 0.0f;
                lp.knownOpen = ALLOW_EDGE_LOCK;
                if (checkDrawerViewAbsoluteGravity(drawerView, 80)) {
                    this.mParallaxOffset = lp.onScreen;
                    updateWallpaperParallax();
                }
            } else if (checkDrawerViewAbsoluteGravity(drawerView, 3)) {
                this.mLeftDragger.smoothSlideViewTo(drawerView, -drawerView.getWidth(), drawerView.getTop());
            } else if (checkDrawerViewAbsoluteGravity(drawerView, 5)) {
                this.mRightDragger.smoothSlideViewTo(drawerView, getWidth(), drawerView.getTop());
            } else {
                this.mBottomDragger.smoothSlideViewTo(drawerView, drawerView.getLeft(), getHeight());
            }
            invalidate();
            return;
        }
        throw new IllegalArgumentException("View " + drawerView + " is not a sliding drawer");
    }

    public void closeDrawer(int gravity) {
        View drawerView = findDrawerWithGravity(gravity);
        if (drawerView == null) {
            throw new IllegalArgumentException("No drawer view found with gravity " + gravityToString(gravity));
        }
        closeDrawer(drawerView);
    }

    public boolean isDrawerOpen(View drawer) {
        if (isDrawerView(drawer)) {
            return ((LayoutParams) drawer.getLayoutParams()).knownOpen;
        }
        throw new IllegalArgumentException("View " + drawer + " is not a drawer");
    }

    public boolean isDrawerOpen(int drawerGravity) {
        View drawerView = findDrawerWithGravity(drawerGravity);
        if (drawerView != null) {
            return isDrawerOpen(drawerView);
        }
        return ALLOW_EDGE_LOCK;
    }

    public boolean isDrawerVisible(View drawer) {
        if (isDrawerView(drawer)) {
            return ((LayoutParams) drawer.getLayoutParams()).onScreen > 0.0f ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
        } else {
            throw new IllegalArgumentException("View " + drawer + " is not a drawer");
        }
    }

    public boolean isDrawerVisible(int drawerGravity) {
        View drawerView = findDrawerWithGravity(drawerGravity);
        if (drawerView != null) {
            return isDrawerVisible(drawerView);
        }
        return ALLOW_EDGE_LOCK;
    }

    private boolean hasPeekingDrawer() {
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            if (((LayoutParams) getChildAt(i).getLayoutParams()).isPeeking) {
                return CHILDREN_DISALLOW_INTERCEPT;
            }
        }
        return ALLOW_EDGE_LOCK;
    }

    protected android.view.ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(-1, -1);
    }

    protected android.view.ViewGroup.LayoutParams generateLayoutParams(android.view.ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) {
            return new LayoutParams((LayoutParams) p);
        }
        return p instanceof MarginLayoutParams ? new LayoutParams((MarginLayoutParams) p) : new LayoutParams(p);
    }

    protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
        return ((p instanceof LayoutParams) && super.checkLayoutParams(p)) ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    public android.view.ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    private boolean hasVisibleDrawer() {
        return findVisibleDrawer() != null ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    private View findVisibleDrawer() {
        int childCount = getChildCount();
        for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
            View child = getChildAt(i);
            if (isDrawerView(child) && isDrawerVisible(child)) {
                return child;
            }
        }
        return null;
    }

    void cancelChildViewTouch() {
        if (!this.mChildrenCanceledTouch) {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancelEvent = MotionEvent.obtain(now, now, 3, 0.0f, 0.0f, STATE_IDLE);
            int childCount = getChildCount();
            for (int i = STATE_IDLE; i < childCount; i += STATE_DRAGGING) {
                getChildAt(i).dispatchTouchEvent(cancelEvent);
            }
            cancelEvent.recycle();
            this.mChildrenCanceledTouch = CHILDREN_DISALLOW_INTERCEPT;
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != 4 || !hasVisibleDrawer()) {
            return super.onKeyDown(keyCode, event);
        }
        KeyEventCompat.startTracking(event);
        return CHILDREN_DISALLOW_INTERCEPT;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != 4) {
            return super.onKeyUp(keyCode, event);
        }
        View visibleDrawer = findVisibleDrawer();
        if (visibleDrawer != null && getDrawerLockMode(visibleDrawer) == 0) {
            closeDrawers();
        }
        return visibleDrawer != null ? CHILDREN_DISALLOW_INTERCEPT : ALLOW_EDGE_LOCK;
    }

    public void setCustomBackground(Drawable sentBackground, Drawable sentForeground) {
        ((BlurTextureView) this.mContentView).setCustomBackground(sentBackground);
        ((BlurTextureView) this.mContentView).setCustomForeground(sentForeground);
        ((BlurTextureView) this.mContentView).setBlur(TOUCH_SLOP_SENSITIVITY);
    }
}
