/* SPDX-License-Identifier: GPL-3.0-or-later
 * iPhone-style alignment aid; not associated with Apple.
 * Draws only in the preview: never rotates, crops, or changes saved image pixels/EXIF.
 */
package net.sourceforge.opencamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.Surface;

public final class AppleLevelGuide implements SensorEventListener {
    private static final AppleLevelGuide INSTANCE = new AppleLevelGuide();
    private static final String PREFS = "apple_style_level_v1";
    private final LevelGuideCore core = new LevelGuideCore();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SensorManager manager;
    private boolean listening, available, sampleReady;
    private float gx, gy, gz;
    private long sampleTime;
    private AppleLevelGuide() {}

    private static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    public static void onResume(MainActivity activity) {
        if (enabled(activity)) INSTANCE.start(activity); else INSTANCE.stop();
    }

    public static void onPause() { INSTANCE.stop(); }

    private synchronized void start(Context context) {
        if (listening) return;
        manager = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        available = false; sampleReady = false; core.reset();
        if (manager == null) return;
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (sensor != null) available = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        listening = available;
    }

    private synchronized void stop() {
        if (manager != null && listening) manager.unregisterListener(this);
        listening = false; sampleReady = false; core.reset();
    }

    @Override public synchronized void onSensorChanged(SensorEvent event) {
        if (event.values.length < 3) return;
        // Smooth hand tremor and the accelerometer fallback; gravity sensor is preferred.
        float alpha = sampleReady ? 0.22f : 1.0f;
        gx += alpha * (event.values[0] - gx);
        gy += alpha * (event.values[1] - gy);
        gz += alpha * (event.values[2] - gz);
        sampleReady = true;
        sampleTime = SystemClock.elapsedRealtime();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public static void draw(MainActivity activity, Canvas canvas) {
        if (!enabled(activity) || activity.isCameraInBackground()) return;
        INSTANCE.drawInternal(activity, canvas);
    }

    private synchronized void drawInternal(MainActivity a, Canvas canvas) {
        if (!listening) return; // Sensor availability is explained in the settings summary.
        long now = SystemClock.elapsedRealtime();
        if (!sampleReady || now - sampleTime > 1200 || a.getPreview() == null) { core.reset(); return; }
        int display = a.getWindowManager().getDefaultDisplay().getRotation();
        int displayDegrees = display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180
                : display == Surface.ROTATION_270 ? 270 : 0;
        int uiRotation = a.getPreview().getUIRotation();
        LevelGuideCore.Reading r = core.update(gx, gy, gz, displayDegrees + uiRotation, now);
        if (!r.valid) return;
        float density = a.getResources().getDisplayMetrics().density;
        float scale = Math.min(density, Math.min(canvas.getWidth(), canvas.getHeight()) / 220f);
        float cx = canvas.getWidth() * 0.5f;
        float cy = canvas.getHeight() * 0.48f;
        int color = r.aligned ? Color.rgb(255, 214, 10) : Color.WHITE;
        canvas.save();
        try {
            canvas.rotate(uiRotation, cx, cy);
            if (r.flat) {
                if (!r.aligned) cross(canvas, cx, cy, 8 * scale, Color.argb(170, 195, 200, 208), scale);
                float dx = r.aligned ? 0 : clamp(r.tiltX * 2.6f, -32, 32) * scale;
                float dy = r.aligned ? 0 : -clamp(r.tiltY * 2.6f, -32, 32) * scale;
                cross(canvas, cx + dx, cy + dy, 8 * scale, color, scale);
            } else {
                int reference = r.aligned ? color : Color.argb(170, 220, 224, 230);
                line(canvas, cx - 58 * scale, cy, cx - 24 * scale, cy, reference, scale);
                line(canvas, cx + 24 * scale, cy, cx + 58 * scale, cy, reference, scale);
                canvas.save();
                canvas.rotate(r.aligned ? 0 : clamp(-r.roll, -35, 35), cx, cy);
                line(canvas, cx - 22 * scale, cy, cx + 22 * scale, cy, color, scale);
                canvas.restore();
            }
            // No text badge or opaque rectangle over the scene.
        } finally { canvas.restore(); }
    }

    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
    private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float s) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(1.8f * s); paint.setColor(Color.argb(75, 0, 0, 0));
        c.drawLine(x1, y1, x2, y2, paint);
        paint.setStrokeWidth(1.0f * s); paint.setColor(color);
        c.drawLine(x1, y1, x2, y2, paint);
    }
    private void cross(Canvas c, float x, float y, float size, int color, float s) {
        line(c, x - size, y, x + size, y, color, s);
        line(c, x, y - size, x, y + size, color, s);
    }

    public static void addSettings(final PreferenceFragment fragment) {
        if (fragment.getPreferenceScreen().findPreference("apple_style_level_entry") != null) return;
        final MainActivity a = (MainActivity) fragment.getActivity();
        CheckBoxPreference item = new CheckBoxPreference(a);
        item.setKey("apple_style_level_entry");
        item.setPersistent(false);
        item.setOrder(-99);
        item.setTitle("水平辅助（细线）");
        item.setSummary("默认关闭；开启后显示细水平线 / 小十字，对齐变黄。仅取景提示，不修改照片。");
        item.setChecked(enabled(a));
        SensorManager sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null || (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) == null
                && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null)) {
            item.setEnabled(false);
            item.setSummary("设备没有可用的重力/加速度传感器，无法提供水平提示。");
        }
        item.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object value) {
                boolean requested = Boolean.TRUE.equals(value);
                boolean saved = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean("enabled", requested).commit();
                if (!saved) {
                    PhotoNameQueue.reportError(a, new java.io.IOException("无法保存水平辅助设置。"));
                    return false;
                }
                if (requested) INSTANCE.start(a); else INSTANCE.stop();
                return true;
            }
        });
        fragment.getPreferenceScreen().addPreference(item);
    }
}
