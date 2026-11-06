package io.codex.stepviewer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Consumes Android SensorManager only. It never generates or imports step values. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int REQUEST_ACTIVITY = 7;
    private static final String TAG = "StepViewerTest";
    private SensorManager sensorManager;
    private Sensor counterSensor;
    private Sensor detectorSensor;
    private TextView stateView;
    private TextView sensorView;
    private TextView counterView;
    private TextView detectorView;
    private TextView timeView;
    private boolean wantedListening;
    private boolean listening;
    private boolean foreground;
    private boolean hasCounterValue;
    private float counterValue;
    private long counterCallbacks;
    private long detectorCallbacks;
    private long latestTimestamp;
    private int latestAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private String latestKind = "尚无事件";
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiRefresh = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            updateValues();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "activity-create pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (state != null) {
            wantedListening = state.getBoolean("wanted");
            hasCounterValue = state.getBoolean("hasCounter");
            counterValue = state.getFloat("counter");
            counterCallbacks = state.getLong("counterCallbacks");
            detectorCallbacks = state.getLong("detectorCallbacks");
            latestTimestamp = state.getLong("timestamp");
            latestAccuracy = state.getInt("accuracy", SensorManager.SENSOR_STATUS_UNRELIABLE);
            latestKind = state.getString("kind", "尚无事件");
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);
        TextView title = addText(column, "计步接收测试", 26);
        title.setContentDescription("计步接收测试");
        addText(column, "仅通过 Android 传感器接口接收数据。本程序不生成步数，不连接运动世界，也不证明真实走路。", 16);
        stateView = addText(column, "状态：未开始监听", 18);
        sensorView = addText(column, "", 16);
        counterView = addText(column, "", 20);
        detectorView = addText(column, "", 20);
        timeView = addText(column, "", 15);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(buttons);
        Button start = new Button(this);
        start.setText("开始监听");
        start.setOnClickListener(v -> { wantedListening = true; requestStart(); });
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button stop = new Button(this);
        stop.setText("停止监听");
        stop.setOnClickListener(v -> { wantedListening = false; stopListening(); stateView.setText("状态：已停止监听"); });
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button refresh = new Button(this);
        refresh.setText("重新检查传感器");
        refresh.setOnClickListener(v -> { boolean resume = wantedListening; stopListening(); discoverSensors(); if (resume) requestStart(); });
        column.addView(refresh);
        addText(column, "累计步数为传感器原始累计值；事件次数仅从本次测试开始统计。切换到后台会暂停监听，返回后按原开关恢复。", 14);
        setContentView(scroll);
        discoverSensors();
        updateValues();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView addText(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, dp(7), 0, dp(7));
        view.setTextIsSelectable(true);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void discoverSensors() {
        if (sensorManager == null) {
            counterSensor = null;
            detectorSensor = null;
            sensorView.setText("未发现计步传感器：SensorManager 不可用");
            Log.w(TAG, "discovery: SensorManager unavailable");
            return;
        }
        try {
            counterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            detectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            Log.i(TAG, "discovery counter=" + describe(counterSensor) + " detector=" + describe(detectorSensor));
            sensorView.setText("STEP_COUNTER：" + describe(counterSensor) + "\nSTEP_DETECTOR：" + describe(detectorSensor));
            if (counterSensor == null && detectorSensor == null) stateView.setText("状态：未发现计步传感器");
        } catch (RuntimeException e) {
            counterSensor = null;
            detectorSensor = null;
            sensorView.setText("检查传感器失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            Log.e(TAG, "discovery failed", e);
        }
    }

    private String describe(Sensor sensor) {
        if (sensor == null) return "未发现计步传感器";
        return sensor.getName() + " / " + sensor.getVendor() + " / 类型 " + sensor.getType();
    }

    private boolean hasActivityPermission() {
        return Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStart() {
        if (!foreground) return;
        if (!hasActivityPermission()) {
            Log.i(TAG, "registration awaiting ACTIVITY_RECOGNITION permission");
            stateView.setText("状态：等待身体活动权限");
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQUEST_ACTIVITY);
            return;
        }
        startListening();
    }

    private void startListening() {
        stopListening();
        discoverSensors();
        if (counterSensor == null && detectorSensor == null) {
            stateView.setText("状态：未发现计步传感器，无法监听");
            Log.w(TAG, "registration skipped: no step sensors");
            return;
        }
        try {
            // A compatibility layer may deliver its first sample during registration.
            listening = true;
            boolean counterOk = counterSensor != null && sensorManager.registerListener(this, counterSensor, SensorManager.SENSOR_DELAY_NORMAL);
            boolean detectorOk = detectorSensor != null && sensorManager.registerListener(this, detectorSensor, SensorManager.SENSOR_DELAY_NORMAL);
            listening = counterOk || detectorOk;
            Log.i(TAG, "registration counter=" + counterOk + " detector=" + detectorOk + " listening=" + listening);
            stateView.setText("状态：" + (listening ? "正在监听" : "监听注册失败") + "；累计=" + counterOk + "，检测=" + detectorOk);
        } catch (RuntimeException e) {
            stopListening();
            stateView.setText("状态：监听失败 " + e.getClass().getSimpleName() + " " + e.getMessage());
            Log.e(TAG, "registration failed", e);
        }
    }

    private void stopListening() {
        Log.i(TAG, "unregister previousListening=" + listening + " wanted=" + wantedListening);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        listening = false;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!listening || event == null || event.sensor == null || event.values == null || event.values.length == 0) return;
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_STEP_COUNTER) {
            counterValue = event.values[0];
            hasCounterValue = true;
            counterCallbacks++;
            latestKind = "STEP_COUNTER";
            Log.i(TAG, "counter value=" + counterValue + " callbacks=" + counterCallbacks + " timestampNs=" + event.timestamp);
        } else if (type == Sensor.TYPE_STEP_DETECTOR) {
            detectorCallbacks++;
            latestKind = "STEP_DETECTOR，值=" + event.values[0];
            Log.i(TAG, "detector value=" + event.values[0] + " total=" + detectorCallbacks + " timestampNs=" + event.timestamp);
        } else return;
        latestTimestamp = event.timestamp;
        latestAccuracy = event.accuracy;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { latestAccuracy = accuracy; }

    private void updateValues() {
        counterView.setText("累计步数：" + (hasCounterValue ? String.format(Locale.ROOT, "%.0f", counterValue) : "尚未收到") + "\n累计传感器回调次数：" + counterCallbacks);
        detectorView.setText("逐步检测事件次数：" + detectorCallbacks);
        timeView.setText("最近事件：" + latestKind + "\n传感器时间戳（启动后纳秒）：" + latestTimestamp + "\n事件精度状态：" + latestAccuracy);
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        Log.i(TAG, "activity-start wanted=" + wantedListening);
        updateValues();
        uiHandler.removeCallbacks(uiRefresh);
        uiHandler.postDelayed(uiRefresh, 1000L);
        if (wantedListening) requestStart();
    }

    @Override protected void onStop() {
        foreground = false;
        Log.i(TAG, "activity-stop wanted=" + wantedListening);
        uiHandler.removeCallbacks(uiRefresh);
        stopListening();
        stateView.setText("状态：后台暂停监听");
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ACTIVITY) {
            if (hasActivityPermission() && wantedListening) requestStart();
            else { wantedListening = false; stateView.setText("状态：未获身体活动权限，未开始监听"); }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("wanted", wantedListening);
        state.putBoolean("hasCounter", hasCounterValue);
        state.putFloat("counter", counterValue);
        state.putLong("counterCallbacks", counterCallbacks);
        state.putLong("detectorCallbacks", detectorCallbacks);
        state.putLong("timestamp", latestTimestamp);
        state.putInt("accuracy", latestAccuracy);
        state.putString("kind", latestKind);
        super.onSaveInstanceState(state);
    }
}
