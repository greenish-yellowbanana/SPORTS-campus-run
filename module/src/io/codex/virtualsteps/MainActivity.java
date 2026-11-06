package io.codex.virtualsteps;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** No network, real-world tracking, login, account or root capability. */
public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, total, cadenceLabel;
    private SeekBar cadence;
    private SharedPreferences p;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(p.getBoolean("enabled", false) ? "状态：正在产生模拟步数" : "状态：已暂停");
            long cumulative = StepState.current(p);
            total.setText("本次模拟步数：" + Math.max(0, cumulative - p.getLong("sessionOrigin", 0))
                    + "\n传感器累计步数：" + cumulative);
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        p = StepState.prefs(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(16)); root.setBackgroundColor(0xfff4f7fb);
        TextView title = label("模拟计步测试", 28); title.setTextColor(0xff173c66); root.addView(title);
        root.addView(label("这是人工生成的测试数据，不是真实行走记录。", 16));
        root.addView(label("在 Vector 中仅勾选需要测试的 App。它们通过 Android 传感器接口读取模拟步数；其他 App 不受影响。", 15));
        status = label("", 21); root.addView(status);
        total = label("", 27); root.addView(total);
        cadenceLabel = label("", 19); root.addView(cadenceLabel);
        cadence = new SeekBar(this); cadence.setMax(200); cadence.setProgress(p.getInt("cadence", 110) - 40);
        root.addView(cadence, new LinearLayout.LayoutParams(-1, dp(52)));
        cadenceLabel.setText("步频：" + (40 + cadence.getProgress()) + " 步/分钟");
        cadence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                cadenceLabel.setText("步频：" + (40 + progress) + " 步/分钟");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                StepState.change(MainActivity.this, p.getBoolean("enabled", false), 40 + bar.getProgress(), false);
            }
        });
        addButton(root, "开始模拟计步", v -> StepState.change(this, true, getCadence(), false));
        addButton(root, "暂停模拟计步", v -> StepState.change(this, false, getCadence(), false));
        addButton(root, "清零本次显示", v -> StepState.change(this, false, getCadence(), true));
        root.addView(label("传感器标签：Codex Virtual Test\n支持 STEP_COUNTER 与 STEP_DETECTOR 的 Java SensorManager 监听。\n未接入的原生传感器接口或目标 App 自有风控不在验证范围内。", 14));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root);
        setContentView(scroll);
    }
    private int getCadence() { return 40 + cadence.getProgress(); }
    private TextView label(String text, int size) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(Color.DKGRAY);
        t.setPadding(0, dp(6), 0, dp(6)); return t;
    }
    private void addButton(LinearLayout root, String text, View.OnClickListener action) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(action);
        root.addView(b, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private int dp(int x) { return (int)(getResources().getDisplayMetrics().density * x + .5f); }
    @Override protected void onResume() { super.onResume(); ui.removeCallbacks(refresh); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
}
