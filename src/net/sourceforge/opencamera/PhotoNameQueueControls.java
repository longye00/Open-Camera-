/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.sourceforge.opencamera;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small touchable identifier selector in the viewfinder. Never drawn into saved photos. */
public final class PhotoNameQueueControls {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static MainActivity owner;
    private static ViewGroup parent;
    private static LinearLayout bar, center;
    private static TextView title, subtitle;
    private static Chevron previous, next;
    private static int lastWidth, lastHeight, lastRotation;
    private PhotoNameQueueControls() {}

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            MainActivity a = owner;
            if (a == null || bar == null) return;
            if (a.isFinishing() || a.isDestroyed()) { detach(); return; }
            try { refresh(a); }
            catch (RuntimeException error) {
                title.setText("编号状态异常");
                subtitle.setText("请检查设置，不要清除数据");
                previous.setEnabled(false); next.setEnabled(false);
            }
            UI.postDelayed(this, 200);
        }
    };

    public static void attach(final MainActivity a) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            a.runOnUiThread(new Runnable() { @Override public void run() { attach(a); } });
            return;
        }
        detach();
        View root = a.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) return;
        parent = (ViewGroup) root;
        owner = a;
        lastWidth = lastHeight = -1; lastRotation = -1;
        bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(224, 22, 26, 30));
        background.setCornerRadius(dp(a, 12));
        background.setStroke(Math.max(1, dp(a, 0.5f)), Color.argb(48, 255, 255, 255));
        bar.setBackground(background);
        previous = new Chevron(a, true);
        next = new Chevron(a, false);
        previous.setContentDescription("上一个拍摄编号");
        next.setContentDescription("下一个拍摄编号");
        center = new LinearLayout(a);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
        center.setFocusable(true);
        title = text(a, 16, Color.WHITE);
        subtitle = text(a, 14, Color.rgb(196, 204, 211));
        LinearLayout titleLine = new LinearLayout(a);
        titleLine.setOrientation(LinearLayout.HORIZONTAL); titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Chevron dropdown = new Chevron(a, false);
        dropdown.setRotation(90); dropdown.setFocusable(false); dropdown.setClickable(false);
        dropdown.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleLine.addView(dropdown, new LinearLayout.LayoutParams(dp(a, 16), dp(a, 16)));
        center.addView(titleLine, new LinearLayout.LayoutParams(-1, -2));
        center.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(previous, new LinearLayout.LayoutParams(dp(a, 44), -1));
        bar.addView(center, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(next, new LinearLayout.LayoutParams(dp(a, 44), -1));
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { PhotoNameQueue.stepSelection(a, -1); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { PhotoNameQueue.stepSelection(a, 1); }
        });
        center.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { PhotoNameQueue.openSelection(a); }
        });
        center.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { PhotoNameQueue.openManager(a); return true; }
        });
        parent.addView(bar, new FrameLayout.LayoutParams(dp(a, 288), dp(a, 56), Gravity.TOP | Gravity.LEFT));
        UI.post(TICK);
    }

    public static void detach() {
        UI.removeCallbacks(TICK);
        if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
        owner = null; parent = null; bar = null; center = null;
        title = subtitle = null; previous = next = null;
    }

    private static TextView text(MainActivity a, float size, int color) {
        TextView t = new TextView(a);
        t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static void refresh(MainActivity a) {
        if (a.isCameraInBackground() || a.getPreview() == null || a.getPreview().isVideo()
                || PhotoNameQueue.isExternalCapture(a)) {
            bar.setVisibility(View.GONE); return;
        }
        bar.setVisibility(View.VISIBLE);
        PhotoNameQueueCore.State state = PhotoNameQueue.snapshot(a);
        String primary, secondary;
        if (state.names.isEmpty()) {
            primary = "导入拍摄编号"; secondary = "点此粘贴一列编号";
        } else {
            primary = state.currentName();
            secondary = state.pending ? (PhotoNameQueue.isBusy() ? "正在保存…" : "保存状态待确认 · 点此查看")
                    : !state.enabled ? "编号拍摄已暂停 · 点此启用"
                    : "已拍 " + state.countFor(state.currentName()) + " 张 · " + (state.autoAdvance ? "自动" : "手动");
        }
        if (!primary.contentEquals(title.getText())) title.setText(primary);
        if (!secondary.contentEquals(subtitle.getText())) subtitle.setText(secondary);
        center.setContentDescription(primary + "，" + secondary + "。点击选择编号，长按管理名单。");
        boolean idle = !PhotoNameQueue.isBusy() && !a.getPreview().isTakingPhotoOrOnTimer();
        center.setEnabled(idle);
        previous.setEnabled(idle && !state.pending && state.selected > 0);
        next.setEnabled(idle && !state.pending && state.selected + 1 < state.names.size());
        previous.invalidate(); next.invalidate();
        int width = parent.getWidth(), height = parent.getHeight();
        int rotation = ((a.getPreview().getUIRotation() % 360) + 360) % 360;
        if (width <= 0 || height <= 0) return;
        if (width != lastWidth || height != lastHeight || rotation != lastRotation) {
            boolean quarter = rotation == 90 || rotation == 270;
            int uiWidth = quarter ? height : width;
            int uiHeight = quarter ? width : height;
            int w = Math.min(dp(a, 288), Math.max(dp(a, 132), uiWidth - dp(a, 24)));
            int h = Math.max(dp(a, 56), Math.round(40.5f * a.getResources().getDisplayMetrics().scaledDensity) + dp(a, 8));
            float offset = -uiHeight / 2f + dp(a, 16) + h / 2f;
            double theta = Math.toRadians(rotation);
            float cx = width / 2f - (float) Math.sin(theta) * offset;
            float cy = height / 2f + (float) Math.cos(theta) * offset;
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
            p.leftMargin = Math.round(cx - w / 2f); p.topMargin = Math.round(cy - h / 2f);
            bar.setLayoutParams(p); bar.setRotation(rotation);
            lastWidth = width; lastHeight = height; lastRotation = rotation;
        }
    }

    private static int dp(MainActivity a, float value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    private static final class Chevron extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean left;
        Chevron(MainActivity a, boolean left) { super(a); this.left = left; setFocusable(true); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float x = getWidth() / 2f, y = getHeight() / 2f, sign = left ? -1 : 1;
            paint.setColor(isEnabled() ? Color.WHITE : Color.rgb(103, 111, 118));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(1.5f * d);
            canvas.drawLine(x - sign * 3 * d, y - 6 * d, x + sign * 3 * d, y, paint);
            canvas.drawLine(x + sign * 3 * d, y, x - sign * 3 * d, y + 6 * d, paint);
        }
    }
}
