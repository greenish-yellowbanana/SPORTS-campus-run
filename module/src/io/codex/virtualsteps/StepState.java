package io.codex.virtualsteps;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** Controller state is private; the read-only provider exposes only synthetic test parameters. */
final class StepState {
    static final String PREFS = "virtual_step_config";
    @SuppressWarnings("deprecation")
    static synchronized SharedPreferences prefs(Context context) {
        SharedPreferences p;
        try {
            // Supported frameworks grant this only to modules declaring xposedsharedprefs.
            // On a stock system it fails safely and the read-only provider remains usable.
            p = context.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException unsupported) {
            p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        int boot = bootCount(context);
        int savedBoot = p.getInt("bootCount", -1);
        boolean rebooted = (boot >= 0 && savedBoot >= 0 && boot != savedBoot)
                || p.getLong("startElapsedNs", 0) > SystemClock.elapsedRealtimeNanos();
        if (rebooted) {
            p.edit().putBoolean("enabled", false).putLong("baseCount", 0)
                    .putLong("startElapsedNs", 0).putLong("sessionOrigin", 0).putInt("bootCount", boot).commit();
        } else if (boot >= 0 && savedBoot != boot) p.edit().putInt("bootCount", boot).commit();
        return p;
    }
    static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
        catch (Throwable ignored) { return -1; }
    }
    static long count(boolean enabled, long startNs, long base, int cadence, long nowNs) {
        if (!enabled || startNs <= 0 || nowNs < startNs) return Math.max(0L, base);
        long elapsed = nowNs - startNs;
        long added = (long) Math.floor(elapsed / 60000000000.0 * Math.max(40, Math.min(240, cadence)));
        return Math.max(0L, base) + Math.max(0L, added);
    }
    static long current(SharedPreferences p) {
        return count(p.getBoolean("enabled", false), p.getLong("startElapsedNs", 0),
                p.getLong("baseCount", 0), p.getInt("cadence", 110), SystemClock.elapsedRealtimeNanos());
    }
    static void change(Context c, boolean enabled, int cadence, boolean reset) {
        SharedPreferences p = prefs(c);
        long count = current(p);
        p.edit().putBoolean("enabled", reset ? false : enabled).putLong("startElapsedNs", SystemClock.elapsedRealtimeNanos())
                .putLong("baseCount", count).putInt("cadence", Math.max(40, Math.min(240, cadence)))
                .putLong("sessionOrigin", reset ? count : p.getLong("sessionOrigin", 0))
                .putInt("bootCount", bootCount(c)).commit();
        c.getContentResolver().notifyChange(ConfigProvider.URI, null);
    }
}
