/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.sourceforge.opencamera;

/** Sensor-independent orientation math and non-flickering alignment state. */
public final class LevelGuideCore {
    public static final long SETTLE_MS = 350;
    public static final float ALIGN_DEGREES = 1.0f;
    public static final float RELEASE_DEGREES = 1.6f;
    private boolean flatMode;
    private boolean aligned;
    private long insideSince = -1;

    public static final class Reading {
        public final boolean valid, flat, aligned;
        public final float roll, tiltX, tiltY, error;
        Reading(boolean valid, boolean flat, boolean aligned, float roll, float tiltX, float tiltY, float error) {
            this.valid = valid; this.flat = flat; this.aligned = aligned;
            this.roll = roll; this.tiltX = tiltX; this.tiltY = tiltY; this.error = error;
        }
    }

    public void reset() { flatMode = false; aligned = false; insideSince = -1; }

    /** rotationDegrees maps natural device sensor axes into the local UI coordinate system. */
    public Reading update(float gx, float gy, float gz, int rotationDegrees, long now) {
        double norm = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (Double.isNaN(norm) || Double.isInfinite(norm) || norm < 7.0 || norm > 12.5) {
            aligned = false; insideSince = -1;
            return new Reading(false, flatMode, false, 0, 0, 0, 180);
        }
        double theta = Math.toRadians(((rotationDegrees % 360) + 360) % 360);
        double x = Math.cos(theta) * gx - Math.sin(theta) * gy;
        double y = Math.sin(theta) * gx + Math.cos(theta) * gy;
        boolean wasFlat = flatMode;
        double verticalFraction = Math.abs(gz) / norm;
        // Hysteresis avoids switching repeatedly between line and cross near the threshold.
        if (flatMode) flatMode = verticalFraction > 0.86;
        else flatMode = verticalFraction > 0.92;
        if (flatMode != wasFlat) { aligned = false; insideSince = -1; }
        float roll = (float) Math.toDegrees(Math.atan2(x, y));
        while (roll > 90) roll -= 180;
        while (roll < -90) roll += 180;
        float tx = (float) Math.toDegrees(Math.atan2(x, Math.abs(gz)));
        float ty = (float) Math.toDegrees(Math.atan2(y, Math.abs(gz)));
        float error = flatMode ? (float) Math.toDegrees(Math.atan2(Math.hypot(x, y), Math.abs(gz))) : Math.abs(roll);
        if (aligned) {
            if (error > RELEASE_DEGREES) { aligned = false; insideSince = -1; }
        } else if (error <= ALIGN_DEGREES) {
            if (insideSince < 0 || now < insideSince) insideSince = now;
            if (now - insideSince >= SETTLE_MS) aligned = true;
        } else insideSince = -1;
        return new Reading(true, flatMode, aligned, roll, tx, ty, error);
    }
}
