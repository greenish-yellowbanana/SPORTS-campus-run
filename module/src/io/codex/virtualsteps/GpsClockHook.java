package io.codex.virtualsteps;

import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Adapts a MuMu GPS timestamp expressed in uptime milliseconds instead of epoch milliseconds. */
public final class GpsClockHook implements IXposedHookLoadPackage {
    private static final long YEAR_2000_MS = 946_684_800_000L;
    private static final long MATCH_TOLERANCE_MS = 1_000L;
    private static final long MAX_SAMPLE_AGE_MS = 10 * 60_000L;
    private static boolean installed;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (installed || !("com.android.gpstest.osmdroid".equals(load.packageName)
                || "com.zjwh.android_wh_physicalfitness".equals(load.packageName))) return;

        XposedHelpers.findAndHookMethod(Location.class, "getTime", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Location location = (Location) param.thisObject;
                long originalMs = (Long) param.getResult();
                if (!LocationManager.GPS_PROVIDER.equals(location.getProvider())
                        || originalMs < 0 || originalMs >= YEAR_2000_MS) return;

                long sampleNs = location.getElapsedRealtimeNanos();
                if (sampleNs <= 0) return;
                long sampleMs = sampleNs / 1_000_000L;
                if (Math.abs(originalMs - sampleMs) > MATCH_TOLERANCE_MS) return;

                long ageMs = SystemClock.elapsedRealtime() - sampleMs;
                if (ageMs < 0 || ageMs > MAX_SAMPLE_AGE_MS) return;
                long correctedMs = System.currentTimeMillis() - ageMs;
                if (correctedMs >= YEAR_2000_MS) param.setResult(correctedMs);
            }
        });
        installed = true;
        XposedBridge.log("VirtualSteps: GPS clock adapter installed in " + load.packageName);
    }
}
