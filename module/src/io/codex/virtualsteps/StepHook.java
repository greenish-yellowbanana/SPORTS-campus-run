package io.codex.virtualsteps;

import android.app.Application;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Synthetic sensors are attached only to explicitly scoped application processes. */
public final class StepHook implements IXposedHookLoadPackage {
    private static final int COUNTER = 19, DETECTOR = 18;
    private static final String OWNER = "io.codex.virtualsteps";
    private static final Uri STATE = Uri.parse("content://io.codex.virtualsteps.config/state");
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<SensorEventListener, List<Subscription>> SUBSCRIPTIONS = new IdentityHashMap<>();
    private static Sensor counter, detector;
    private static Handler worker;
    private static volatile Context appContext;
    private static boolean installed;
    private static int targetSdk;
    private static long lastErrorLog;
    private static long lastEventTimestamp;
    private static XSharedPreferences sharedFallback;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) throws Throwable {
        if (installed || load.appInfo == null || load.appInfo.uid < 10000 || OWNER.equals(load.packageName)) return;
        installed = true;
        targetSdk = load.appInfo.targetSdkVersion;
        try {
            counter = newSensor(COUNTER); detector = newSensor(DETECTOR);
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof Context) appContext = (Context)p.args[0];
                }
            });
            XposedBridge.hookAllMethods(SensorManager.class, "getSensorList", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 1 || !(p.args[0] instanceof Integer) || !(p.getResult() instanceof List)) return;
                    int type = (Integer)p.args[0];
                    if (type != Sensor.TYPE_ALL && type != COUNTER && type != DETECTOR) return;
                    List<Sensor> list = new ArrayList<>((List<Sensor>)p.getResult());
                    if ((type == Sensor.TYPE_ALL || type == COUNTER) && !hasType(list, COUNTER)) list.add(counter);
                    if ((type == Sensor.TYPE_ALL || type == DETECTOR) && !hasType(list, DETECTOR)) list.add(detector);
                    p.setResult(Collections.unmodifiableList(list));
                }
            });
            XposedBridge.hookAllMethods(SensorManager.class, "getDefaultSensor", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.getResult() != null || p.args.length < 1 || !(p.args[0] instanceof Integer)) return;
                    if (p.args.length > 1 && Boolean.TRUE.equals(p.args[1])) return; // no synthetic wake-up sensor
                    int type = (Integer)p.args[0];
                    if (type == COUNTER) p.setResult(counter);
                    if (type == DETECTOR) p.setResult(detector);
                }
            });
            Class<?> manager = XposedHelpers.findClass("android.hardware.SystemSensorManager", load.classLoader);
            XposedBridge.hookAllMethods(manager, "registerListenerImpl", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 2 || !(p.args[0] instanceof SensorEventListener) || !isVirtual(p.args[1])) return;
                    if ((p.args.length > 2 && p.args[2] instanceof Integer && (Integer)p.args[2] < 0)
                            || (p.args.length > 4 && p.args[4] instanceof Integer && (Integer)p.args[4] < 0)) {
                        p.setResult(false); return;
                    }
                    Context context = resolveContext();
                    if (Build.VERSION.SDK_INT >= 29 && targetSdk >= 29 && context != null &&
                            context.checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        log("listener denied: ACTIVITY_RECOGNITION permission missing"); p.setResult(false); return;
                    }
                    Handler target = null;
                    for (Object arg : p.args) if (arg instanceof Handler) { target = (Handler)arg; break; }
                    if (target == null) target = new Handler(Looper.getMainLooper());
                    subscribe((SensorEventListener)p.args[0], (Sensor)p.args[1], target);
                    p.setResult(true);
                }
            });
            XposedBridge.hookAllMethods(manager, "unregisterListenerImpl", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 2 || !(p.args[0] instanceof SensorEventListener)) return;
                    Object sensor = p.args[1];
                    unsubscribe((SensorEventListener)p.args[0], sensor instanceof Sensor ? (Sensor)sensor : null);
                    if (isVirtual(sensor)) p.setResult(null); // real and all-sensor cleanup remains Android's job
                }
            });
            log("installed synthetic sensors in " + load.packageName + " / " + load.processName);
        } catch (Throwable t) { log("installation failed: " + t); }
    }

    private static boolean hasType(List<Sensor> list, int type) {
        for (Sensor s : list) if (s.getType() == type) return true;
        return false;
    }
    private static boolean isVirtual(Object sensor) { return sensor != null && (sensor == counter || sensor == detector); }
    private static Sensor newSensor(int type) throws Throwable {
        Sensor sensor = (Sensor)XposedHelpers.newInstance(Sensor.class);
        set(sensor, "mName", type == COUNTER ? "Simulated Step Counter (TEST)" : "Simulated Step Detector (TEST)");
        set(sensor, "mVendor", "Codex Virtual Test"); set(sensor, "mVersion", 1);
        set(sensor, "mType", type); set(sensor, "mHandle", 0x7f110000 + type);
        set(sensor, "mStringType", type == COUNTER ? "android.sensor.step_counter" : "android.sensor.step_detector");
        set(sensor, "mMaxRange", type == COUNTER ? 16777216f : 1f);
        set(sensor, "mResolution", 1f); set(sensor, "mPower", 0f); set(sensor, "mMinDelay", 0);
        set(sensor, "mMaxDelay", 0); set(sensor, "mFlags", type == COUNTER ? 2 : 6);
        set(sensor, "mRequiredPermission", "android.permission.ACTIVITY_RECOGNITION");
        if (sensor.getType() != type || !"Codex Virtual Test".equals(sensor.getVendor())) {
            throw new IllegalStateException("Synthetic sensor fields could not be initialized");
        }
        return sensor;
    }
    private static void set(Object target, String name, Object value) {
        try { Field f = XposedHelpers.findField(target.getClass(), name); f.set(target, value); }
        catch (Throwable ignored) { /* Optional platform fields may differ. Core type/handle are checked by returned getters. */ }
    }
    private static void subscribe(SensorEventListener listener, Sensor sensor, Handler target) {
        synchronized (LOCK) {
            List<Subscription> list = SUBSCRIPTIONS.get(listener);
            if (list == null) { list = new ArrayList<>(); SUBSCRIPTIONS.put(listener, list); }
            for (Subscription sub : list) if (sub.sensor == sensor) return;
            list.add(new Subscription(listener, sensor, target));
            if (worker == null) {
                HandlerThread thread = new HandlerThread("VirtualStepsTest"); thread.start();
                worker = new Handler(thread.getLooper()); worker.post(TICK);
            } else { worker.removeCallbacks(TICK); worker.post(TICK); }
        }
        log("listener registered: type=" + sensor.getType());
    }
    private static void unsubscribe(SensorEventListener listener, Sensor sensor) {
        synchronized (LOCK) {
            List<Subscription> list = SUBSCRIPTIONS.get(listener);
            if (list == null) return;
            for (int i = list.size() - 1; i >= 0; i--) {
                Subscription sub = list.get(i);
                if (sensor == null || sub.sensor == sensor) { sub.active = false; sub.target.removeCallbacksAndMessages(sub); list.remove(i); }
            }
            if (list.isEmpty()) SUBSCRIPTIONS.remove(listener);
        }
    }

    private static final Runnable TICK = new Runnable() {
        private Sample sample = new Sample();
        private long nextRead;
        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtimeNanos();
                boolean hasListeners;
                synchronized (LOCK) { hasListeners = !SUBSCRIPTIONS.isEmpty(); }
                if (hasListeners && now >= nextRead) {
                    Sample fresh = readState();
                    if (fresh != null) sample = fresh;
                    else {
                        Sample paused = new Sample(); paused.base = sample.count(now); sample = paused;
                    }
                    nextRead = now + 500000000L;
                }
                long count = sample.count(now);
                List<Subscription> subscriptions = new ArrayList<>();
                synchronized (LOCK) { for (List<Subscription> s : SUBSCRIPTIONS.values()) subscriptions.addAll(s); }
                for (Subscription sub : subscriptions) {
                    if (!sub.active) continue;
                    if (sub.last < 0) {
                        sub.last = count;
                        if (sub.sensor == counter) dispatch(sub, (float)count, now, true);
                        continue;
                    }
                    if (count != sub.last) {
                        long delta = count - sub.last; sub.last = count;
                        if (sub.sensor == counter) dispatch(sub, (float)count, now, false);
                        else if (sample.enabled && delta > 0) {
                            // Bound resumed/background bursts instead of allocating an unbounded callback queue.
                            for (long i = 0; i < Math.min(delta, 8); i++) dispatch(sub, 1f, now, false);
                        }
                    }
                }
            } catch (Throwable t) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastErrorLog > 10000) { log("tick: " + t); lastErrorLog = now; }
            } finally {
                synchronized (LOCK) {
                    worker.removeCallbacks(this);
                    if (!SUBSCRIPTIONS.isEmpty()) worker.postDelayed(this, 100);
                }
            }
        }
    };
    private static Context resolveContext() {
        Context context = appContext;
        if (context == null) context = AndroidAppHelper.currentApplication();
        return context;
    }
    private static Sample readState() {
        Context context = resolveContext();
        if (context == null) return null;
        try (Cursor c = context.getContentResolver().query(STATE, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                Sample s = new Sample(); s.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
                s.start = c.getLong(c.getColumnIndexOrThrow("startElapsedNs"));
                s.base = c.getLong(c.getColumnIndexOrThrow("baseCount"));
                s.cadence = c.getInt(c.getColumnIndexOrThrow("cadence"));
                int bootColumn = c.getColumnIndex("bootCount");
                s.boot = bootColumn >= 0 ? c.getInt(bootColumn) : -1;
                return sanitizeBoot(s, context);
            }
        } catch (Throwable t) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastErrorLog > 10000) { log("provider unavailable; trying framework shared preferences: " + t); lastErrorLog = now; }
        }
        try {
            if (sharedFallback == null) sharedFallback = new XSharedPreferences(OWNER, StepState.PREFS);
            sharedFallback.reload();
            if (sharedFallback.getAll().isEmpty()) return null;
            Sample s = new Sample();
            s.enabled = sharedFallback.getBoolean("enabled", false);
            s.start = sharedFallback.getLong("startElapsedNs", 0);
            s.base = sharedFallback.getLong("baseCount", 0);
            s.cadence = sharedFallback.getInt("cadence", 110);
            s.boot = sharedFallback.getInt("bootCount", -1);
            return sanitizeBoot(s, context);
        } catch (Throwable t) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastErrorLog > 10000) { log("shared preferences unavailable: " + t); lastErrorLog = now; }
            return null;
        }
    }
    private static Sample sanitizeBoot(Sample s, Context context) {
        int boot = StepState.bootCount(context);
        if (s.start > SystemClock.elapsedRealtimeNanos() || (boot >= 0 && s.boot >= 0 && boot != s.boot)) {
            s.enabled = false; s.base = 0; s.start = 0;
        }
        return s;
    }
    private static void dispatch(final Subscription sub, final float value, long now, final boolean first) {
        final long timestamp;
        synchronized (LOCK) { timestamp = Math.max(now, lastEventTimestamp + 1L); lastEventTimestamp = timestamp; }
        Runnable callback = new Runnable() {
            @Override public void run() {
                if (!sub.active) return;
                try {
                    SensorEvent event = (SensorEvent)XposedHelpers.newInstance(SensorEvent.class, 1);
                    event.sensor = sub.sensor; event.values[0] = value; event.timestamp = timestamp;
                    event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
                    if (first) sub.listener.onAccuracyChanged(sub.sensor, event.accuracy);
                    sub.listener.onSensorChanged(event);
                } catch (Throwable t) { log("listener dispatch failed: " + t); }
            }
        };
        sub.target.postAtTime(callback, sub, SystemClock.uptimeMillis());
    }
    private static void log(String message) { XposedBridge.log("VirtualSteps: " + message); }
    private static final class Sample {
        boolean enabled; long start, base; int cadence = 110, boot = -1;
        long count(long now) { return StepState.count(enabled, start, base, cadence, now); }
    }
    private static final class Subscription {
        final SensorEventListener listener; final Sensor sensor; final Handler target;
        volatile boolean active = true; long last = -1;
        Subscription(SensorEventListener listener, Sensor sensor, Handler target) {
            this.listener = listener; this.sensor = sensor; this.target = target;
        }
    }
}
