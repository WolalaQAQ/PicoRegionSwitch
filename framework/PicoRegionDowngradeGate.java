package com.android.server.pm;

import android.os.SystemClock;
import android.os.SystemProperties;

/** Short-lived downgrade exception for the seven region components. */
public final class PicoRegionDowngradeGate {
    private PicoRegionDowngradeGate() {}

    public static boolean allow(String packageName) {
        if (!("com.bytedance.pico.matrix".equals(packageName)
                || "com.picovr.store".equals(packageName)
                || "com.picovr.vrusercenter".equals(packageName)
                || "com.pvr.pvrfit".equals(packageName)
                || "com.picopui.im".equals(packageName)
                || "com.pvr.home".equals(packageName)
                || "com.pvr.avatareditor".equals(packageName))) {
            return false;
        }
        long deadline = SystemProperties.getLong("sys.pico_region_downgrade_until", 0L);
        long now = SystemClock.elapsedRealtime();
        return deadline > now && deadline - now <= 120000L;
    }
}
