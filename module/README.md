# 模拟计步测试：模块与控制器

Package: `io.codex.virtualsteps`. Launcher activity: `.MainActivity`.

Legacy Xposed entry: `assets/xposed_init`, `io.codex.virtualsteps.StepHook`.

The controller has no network or root capability. It writes module preferences; an exported, **read-only** provider publishes only synthetic parameters:

`content://io.codex.virtualsteps.config/state`

Cursor columns: `enabled` (0/1), `startElapsedNs` (monotonic nanoseconds), `baseCount` (long), `cadence` (steps/minute), `count` (computed long), `isSimulated` (always 1), `bootCount` (Android boot counter). Reboot disables simulation and resets the synthetic boot cumulative total.

Exact preference file: `virtual_step_config`. Manifest declares `xposedsharedprefs=true`; controller first tries framework-supported MODE_WORLD_READABLE and safely falls back to private preferences on stock Android. Scoped processes prefer the read-only provider; if package visibility blocks lookup, they fall back to framework-backed XSharedPreferences. No target APK changes are required.

The hook only runs in explicitly scoped ordinary app processes (UID >= 10000); system processes and the controller are excluded. It appends missing STEP_COUNTER and STEP_DETECTOR entries to Java SensorManager enumeration, supplies missing default sensors and intercepts only registrations for these synthetic Sensor instances. Existing hardware sensors and their subscriptions are preserved.

Sensor labels explicitly identify test data (`Codex Virtual Test`, `Simulated Step ... (TEST)`). It does not alter device identity, hide emulator status or access third-party records. Actual motion is not measured.

Callback behavior: initial cumulative counter reading and subsequent changed counts; cadence 40–240 steps/minute. During normal foreground operation, detector events are sent for each simulated step. Delayed catch-up sends at most eight detector events per update to avoid blocking. The “清零本次显示” button pauses and resets only the displayed session origin, preserving the within-boot sensor cumulative count. Callback Handler is respected. Unregister removes pending callbacks. Background polling stops with the last unregister. Config read failures pause at the last known count. Android 10+ target apps must receive ACTIVITY_RECOGNITION permission before registration succeeds. Only controller UI can change simulation state; provider writes throw SecurityException.

## Build inputs

Compile Java sources in `src/` with Android platform API 35 and legacy Xposed API 82 stubs; package manifest and assets, D8 to dex, then sign. Xposed stubs are compile-only and must not be packaged in the APK.

## Runtime verification required

1. Install controller and a separate SensorManager reader APK.
2. Enable module in Vector, scope **only the reader** at first, restart reader process.
3. Grant the reader activity recognition permission. Enumerate types 18 and 19; verify labels and registration.
4. Start at 110 steps/min; counter delta should approximate 11 steps over six seconds and detector events should advance correspondingly.
5. Pause, verify no further growth; restart, verify growth resumes; unregister, verify callbacks cease.
6. Remove reader from scope/restart, verify emulated hardware no longer lists these synthetic sensors.

Android 11+ package visibility can affect provider lookup. Test reader may declare `<queries><provider android:authorities="io.codex.virtualsteps.config"/></queries>` for direct provider diagnostics. Arbitrary scoped apps also need runtime validation of the shared-preference fallback, not an APK manifest modification. Test with and without provider visibility when possible.

This is a Java API compatibility module, not a Sensor HAL. Native/NDK sensor consumers, hardware-backed proof, app-specific behavior or acceptance of exercise records are not established by a successful reader test.
