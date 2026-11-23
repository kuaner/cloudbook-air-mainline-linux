package io.github.kuaner.cloudbook.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int FOREGROUND = Color.rgb(232, 236, 245);
    private static final int SECONDARY = Color.rgb(174, 184, 201);
    private static final int ACCENT = Color.rgb(155, 184, 255);
    private static final int CARD = Color.rgb(42, 48, 60);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AndroidBootSwitch bootSwitch;
    private TextView status;
    private Button checkButton;
    private Button rebootButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bootSwitch = new AndroidBootSwitch(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame);

        LinearLayout content = column();
        content.setPadding(dp(24), dp(32), dp(24), dp(32));
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(
                dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        frame.addView(content, panel);
        frame.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop,
                                         oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(560));
            if (width > 0 && panel.width != width) {
                panel.width = width;
                content.setLayoutParams(panel);
            }
        });

        TextView title = text("切换系统", 32, FOREGROUND);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(content, title, 0);

        LinearLayout slots = column();
        slots.setPadding(dp(20), dp(17), dp(20), dp(17));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(16));
        slots.setBackground(background);
        add(slots, text("当前系统", 13, SECONDARY), 0);
        add(slots, text("Android · B 槽", 21, FOREGROUND), 3);
        add(slots, text("重启目标", 13, SECONDARY), 17);
        add(slots, text("Debian Linux · A 槽", 21, ACCENT), 3);
        add(content, slots, 22);

        status = text("首次检查时，请在 Android 弹窗中允许调试。", 15, SECONDARY);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 16);

        checkButton = new Button(this);
        checkButton.setText("检查启动状态");
        checkButton.setOnClickListener(view -> check());
        add(content, checkButton, 10);

        rebootButton = new Button(this);
        rebootButton.setText("重启到 Debian");
        rebootButton.setEnabled(false);
        rebootButton.setOnClickListener(view -> confirmReboot());
        add(content, rebootButton, 2);
        setContentView(scroll);

        if (!AndroidBootSwitch.DEVICE.equals(Build.DEVICE)) {
            status.setText("此工具适用于运行原厂 Android 的 CloudBook Air CT2000-24RTW。");
            checkButton.setEnabled(false);
        } else if (getPreferences(MODE_PRIVATE).getBoolean("has_checked", false)) {
            check();
        }
    }

    private void check() {
        runOperation(false);
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("重启到 Debian？")
                .setMessage("系统将立即从 Android 重启到 Debian Linux。请先保存工作。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启到 Debian", (dialog, which) -> runOperation(true))
                .show();
    }

    private void runOperation(boolean reboot) {
        checkButton.setEnabled(false);
        rebootButton.setEnabled(false);
        status.setText(reboot ? "正在设置启动槽位…" : "正在检查启动状态…");
        worker.execute(() -> {
            try {
                if (reboot) {
                    bootSwitch.rebootToLinux();
                } else {
                    bootSwitch.check();
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    if (reboot) {
                        status.setText("已选择 Debian，正在重启…");
                    } else {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("has_checked", true).apply();
                        status.setText("启动状态已确认，可以切换系统。");
                        checkButton.setEnabled(true);
                        rebootButton.setEnabled(true);
                    }
                });
            } catch (Exception error) {
                Log.w("CloudBookSwitcher", "System switch operation failed", error);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        status.setText(error instanceof IOException ? error.getMessage() :
                                "检查启动状态失败，请重试。");
                        checkButton.setEnabled(true);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void add(LinearLayout parent, android.view.View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
