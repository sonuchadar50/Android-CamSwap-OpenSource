package io.github.zensu357.camswap;

import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayControlService extends Service {
    private static final int EDGE_MARGIN_DP = 12;
    private static final float SCALE_FACTOR = 0.8f;
    private static final int INNER_GAP_DP = 6;

    private WindowManager windowManager;
    private LinearLayout contentContainer;
    private FrameLayout rootView;
    private LinearLayout actionPanel;
    private TextView bubbleView;
    private WindowManager.LayoutParams layoutParams;
    private ValueAnimator snapAnimator;
    private boolean isExpanded;
    private boolean snappedToRight = true;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(io.github.zensu357.camswap.utils.LocaleHelper.INSTANCE.onAttach(newBase));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showOverlay();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showOverlay() {
        if (windowManager == null || rootView != null) {
            return;
        }

        rootView = new FrameLayout(this);
        rootView.setClipChildren(false);
        rootView.setClipToPadding(false);
        int outerPadding = dpScaled(6);
        rootView.setPadding(outerPadding, outerPadding, outerPadding, outerPadding);

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.HORIZONTAL);
        contentContainer.setGravity(Gravity.CENTER_VERTICAL);
        contentContainer.setClipChildren(false);
        contentContainer.setClipToPadding(false);

        actionPanel = new LinearLayout(this);
        actionPanel.setOrientation(LinearLayout.HORIZONTAL);
        actionPanel.setGravity(Gravity.CENTER_VERTICAL);
        int panelPaddingH = dpScaled(8);
        int panelPaddingV = dpScaled(8);
        actionPanel.setPadding(panelPaddingH, panelPaddingV, panelPaddingH, panelPaddingV);
        actionPanel.setVisibility(View.GONE);
        actionPanel.setBackground(makePanelBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            actionPanel.setElevation(dpScaled(6));
        }

        actionPanel.addView(makeActionButton(getString(R.string.notif_action_next), resolveMonetColor(0xFF6650A4),
                v -> ControlActionHelper.switchVideo(this, true)));
        actionPanel.addView(makeSpacer());
        actionPanel.addView(makeActionButton(getString(R.string.overlay_action_rotate), resolveMonetColor(0xFF7D5260),
                v -> ControlActionHelper.rotateVideo(this)));
        actionPanel.addView(makeSpacer());
        ConfigManager config = new ConfigManager();
        config.setContext(this);
        config.forceReload();
        boolean original = config.getBoolean(ConfigManager.KEY_USE_ORIGINAL_CAMERA, true);
        actionPanel.addView(makeActionButton(getString(original
                        ? R.string.overlay_action_virtual_camera : R.string.overlay_action_original_camera),
                resolveMonetColor(0xFF386A20), v -> {
                    boolean useOriginal = ControlActionHelper.toggleOriginalCamera(this);
                    ((TextView) v).setText(getString(useOriginal
                            ? R.string.overlay_action_virtual_camera : R.string.overlay_action_original_camera));
                }));

        bubbleView = new TextView(this);
        bubbleView.setText("CS");
        bubbleView.setTextColor(resolveOnAccentColor());
        bubbleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        bubbleView.setBackground(makeBubbleBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bubbleView.setElevation(dpScaled(6));
        }

        int bubbleSize = dpScaled(45);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(bubbleSize, bubbleSize);
        bubbleView.setLayoutParams(bubbleParams);
        bubbleView.setOnTouchListener(new BubbleTouchListener());

        rootView.addView(contentContainer);

        applySideLayout();

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = getResources().getDisplayMetrics().widthPixels - dpScaled(60);
        layoutParams.y = dpScaled(176);

        windowManager.addView(rootView, layoutParams);
        rootView.post(() -> updateOverlayPosition(false));
    }

    private void removeOverlay() {
        cancelSnapAnimator();
        if (windowManager != null && rootView != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception ignored) {
            }
        }
        contentContainer = null;
        rootView = null;
        actionPanel = null;
        bubbleView = null;
    }

    private void toggleExpanded() {
        isExpanded = !isExpanded;
        if (actionPanel != null) {
            actionPanel.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        }
        if (rootView != null) {
            rootView.post(() -> updateOverlayPosition(true));
        }
    }

    private View makeSpacer() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dpScaled(6),
