package io.github.kuaner.cloudbook.switcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private enum Action {
        SHUTDOWN("关机", "设备将关机。请先保存工作。", "已请求关机…"),
        REBOOT("重启", "设备将按当前启动设置重启。请先保存工作。", "已请求重启…"),
        LINUX("Linux", "设备将重启到 Linux。若要启动内置系统，请先拔掉试跑 U 盘。", "已选择 Linux，正在重启…"),
        CHECK("检查启动状态", "", "Linux 启动条件已确认。");

        final String label;
        final String confirmation;
        final String success;

        Action(String label, String confirmation, String success) {
            this.label = label;
            this.confirmation = confirmation;
            this.success = success;
        }
    }

    private static final AtomicBoolean OPERATION_RUNNING = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> buttons = new ArrayList<>();
    private AndroidBootSwitch bootSwitch;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bootSwitch = new AndroidBootSwitch(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(frame);

        LinearLayout panel = column();
        panel.setBackground(surface(R.color.power_background, R.color.power_border, 16));
        panel.setPadding(dp(18), dp(20), dp(18), dp(12));
        FrameLayout.LayoutParams panelSize = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        frame.addView(panel, panelSize);
        frame.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left - frame.getPaddingLeft() - frame.getPaddingRight(), dp(560));
            if (width > 0 && panelSize.width != width) {
                panelSize.width = width;
                panel.setLayoutParams(panelSize);
            }
        });

        add(panel, header(), 0);
        LinearLayout actions = new LinearLayout(this);
        addTile(actions, Action.SHUTDOWN, R.drawable.ic_power, 0);
        addTile(actions, Action.REBOOT, R.drawable.ic_reboot, 8);
        addTile(actions, Action.LINUX, R.drawable.ic_linux, 8);
        add(panel, actions, 20);

        Button check = button(Action.CHECK);
        check.setTextColor(color(R.color.power_accent));
        check.setMinHeight(dp(48));
        check.setPadding(dp(10), dp(8), dp(10), dp(8));
        check.setBackground(buttonBackground(android.R.color.transparent));
        LinearLayout.LayoutParams checkSize = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        checkSize.topMargin = dp(6);
        checkSize.gravity = Gravity.END;
        panel.addView(check, checkSize);

        status = text("", 14, R.color.power_secondary);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(panel, status, 6);
        setContentView(scroll);

        if (!AndroidBootSwitch.DEVICE.equals(Build.DEVICE)) {
            setButtonsEnabled(false);
            showStatus("此工具适用于运行原厂 Android 的 CloudBook Air CT2000-24RTW。", true);
        }
    }

    private LinearLayout header() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_power);
        icon.setImageTintList(ColorStateList.valueOf(color(R.color.power_accent)));
        icon.setBackground(surface(R.color.power_focus, 0, 12));
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = column();
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -2, 1);
        titleSize.setMarginStart(dp(12));
        header.addView(titles, titleSize);
        TextView title = text("电源", 24, R.color.power_text);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        add(titles, title, 0);
        add(titles, text("CloudBook Air · Android", 14, R.color.power_secondary), 2);
        return header;
    }

    private void addTile(LinearLayout row, Action action, int icon, int startMargin) {
        Button tile = button(action);
        tile.setMinHeight(dp(88));
        tile.setPadding(dp(4), dp(14), dp(4), dp(14));
        tile.setBackground(buttonBackground(R.color.power_tile));
        tile.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        tile.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
        size.setMarginStart(dp(startMargin));
        row.addView(tile, size);
    }

    private Button button(Action action) {
        Button button = new Button(this);
        button.setText(action.label);
        button.setTextSize(15);
        button.setTextColor(color(R.color.power_text));
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setOnClickListener(view -> {
            if (action == Action.CHECK) {
                runOperation(action);
            } else {
                confirm(action);
            }
        });
        buttons.add(button);
        return button;
    }

    private void confirm(Action action) {
        String title = action == Action.LINUX ? "重启到 Linux" : action.label;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title + "？")
                .setMessage(action.confirmation)
                .setNegativeButton("取消", null)
                .setPositiveButton(title, (confirmed, which) -> runOperation(action))
                .create();
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(surface(R.color.power_background, R.color.power_border, 16));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void runOperation(Action action) {
        if (!OPERATION_RUNNING.compareAndSet(false, true)) {
            showStatus("已有电源操作在执行，请稍候。", false);
            return;
        }
        setButtonsEnabled(false);
        showStatus(switch (action) {
            case CHECK -> "正在检查启动状态…";
            case LINUX -> "正在验证并设置 Linux 启动槽…";
            case REBOOT -> "正在请求重启…";
            case SHUTDOWN -> "正在请求关机…";
        }, false);
        worker.execute(() -> {
            try {
                switch (action) {
                    case CHECK -> bootSwitch.check();
                    case SHUTDOWN -> bootSwitch.shutdown();
                    case REBOOT -> bootSwitch.reboot();
                    case LINUX -> bootSwitch.rebootToLinux();
                }
                runOnUiThread(() -> complete(action.success, false, action == Action.CHECK));
            } catch (Exception error) {
                Log.w("CloudBookSwitcher", "Power operation failed: " + action, error);
                String message = error instanceof IOException ? error.getMessage() : "电源操作失败，请重试。";
                runOnUiThread(() -> complete(message, true, true));
            } finally {
                OPERATION_RUNNING.set(false);
            }
        });
    }

    private void complete(String message, boolean error, boolean enableButtons) {
        if (!isDestroyed()) {
            showStatus(message, error);
            setButtonsEnabled(enableButtons);
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Button button : buttons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : .5f);
        }
    }

    private void showStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(color(error ? R.color.power_error : R.color.power_secondary));
        status.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private Drawable buttonBackground(int fill) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, surface(R.color.power_focus, R.color.power_accent, 12));
        states.addState(new int[]{android.R.attr.state_hovered}, surface(R.color.power_focus, 0, 12));
        states.addState(new int[0], surface(fill, 0, 12));
        return new RippleDrawable(ColorStateList.valueOf(0x30da6e89), states, surface(android.R.color.white, 0, 12));
    }

    private GradientDrawable surface(int fill, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(fill));
        background.setCornerRadius(dp(radius));
        if (border != 0) {
            background.setStroke(dp(1), color(border));
        }
        return background;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color(textColor));
        return view;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private int color(int resource) {
        return getColor(resource);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
