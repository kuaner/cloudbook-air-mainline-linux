// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff10151d;
    private static final int PANEL = 0xff1a2330;
    private static final int FIELD = 0xff111a26;
    private static final int TEXT = 0xffedf4ff;
    private static final int MUTED = 0xffb3c1d5;
    private static final int ACCENT = 0xffa9cdff;
    private static final int DANGER = 0xffffaca9;
    private static final int[][] RESOLUTIONS = {{1920, 1200}, {1920, 1080}, {1280, 800}};
    private static final String[] QUALITY_NAMES = {"1920 × 1200 · 适配 CloudBook", "1920 × 1080", "1280 × 800 · 节省带宽"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText host;
    private EditText user;
    private EditText password;
    private Spinner quality;
    private CheckBox virtualScreen;
    private CheckBox audioOption;
    private CheckBox directDecode;
    private TextView formError;
    private RemoteSession session;
    private RemoteViewport viewport;
    private FrameLayout screen;
    private View waiting;
    private TextView waitingLabel;
    private TextView stats;
    private Button controls;
    private WifiManager.WifiLock wifi;
    private LanDiscovery discovery;
    private boolean sessionScreen;
    private boolean videoConfigured;
    private boolean displayedFrame;
    private boolean muted;
    private boolean audioUnavailable;
    private boolean localMenuShortcut;
    private boolean captureRequested;
    private String decoderName = "";
    private long lastFrames;
    private long lastBytes;
    private long lastStatisticsNs;
    private int viewGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("destination", MODE_PRIVATE);
        showForm(null);
    }

    private void showForm(String error) {
        sessionScreen = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        FrameLayout center = new FrameLayout(this);
        center.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(center, new ScrollView.LayoutParams(-1, -2));
        LinearLayout card = column();
        card.setBackground(shape(PANEL, 20));
        card.setPadding(dp(25), dp(22), dp(25), dp(22));
        int width = Math.min(dp(510), getResources().getDisplayMetrics().widthPixels - dp(40));
        center.addView(card, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));

        TextView title = text("连接 Mac", 28, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(card, title, 0);
        add(card, text("Apple 高性能屏幕共享", 15, MUTED), 3);
        host = field("Mac 地址", prefs.getString("host", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = field("macOS 账户", prefs.getString("user", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        password = field("macOS 密码", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        password.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            connect();
            return true;
        });
        for (View field : new View[]{host, user, password}) add(card, field, 8);

        quality = new Spinner(this);
        quality.setContentDescription("远程屏幕分辨率");
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, QUALITY_NAMES);
        quality.setAdapter(choices);
        quality.setSelection(Math.min(RESOLUTIONS.length - 1, Math.max(0, prefs.getInt("quality", 0))));
        add(card, quality, 3);
        LinearLayout options = new LinearLayout(this);
        virtualScreen = check("虚拟屏幕", prefs.getBoolean("virtualScreen", true));
        audioOption = check("播放 Mac 声音", prefs.getBoolean("audio", true));
        options.addView(virtualScreen);
        options.addView(audioOption);
        add(card, options, 1);
        directDecode = check("硬解直出（暗部和高光可能裁切）", prefs.getBoolean("directDecode", false));
        add(card, directDecode, 0);
        formError = text("", 14, DANGER);
        error(error);
        add(card, formError, 6);

        LinearLayout actions = new LinearLayout(this);
        Button find = button("发现局域网 Mac", false);
        find.setOnClickListener(v -> discover());
        Button connect = button("连接", true);
        connect.setOnClickListener(v -> connect());
        actions.addView(find, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(0, dp(48), 1);
        weight.setMarginStart(dp(9));
        actions.addView(connect, weight);
        add(card, actions, 9);
        center.setFocusableInTouchMode(true);
        setContentView(scroll);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.show(WindowInsets.Type.systemBars());
        center.requestFocus();
    }

    private void discover() {
        if (discovery != null) return;
        hideIme();
        discovery = new LanDiscovery(this, (address, port) -> {
            host.setText(port == 5900 ? address : address + ":" + port);
            user.requestFocus();
        }, () -> discovery = null);
        discovery.show();
    }

    private void connect() {
        String address = host.getText().toString().trim();
        String account = user.getText().toString().trim();
        String secret = password.getText().toString();
        if (address.isEmpty() || account.isEmpty() || secret.isEmpty()) {
            error("请输入 Mac 地址、账户和密码。");
            return;
        }
        String[] endpoint = address.split(":", -1);
        int port = 5900;
        try {
            if (endpoint.length > 2 || !endpoint[0].matches("[\\p{L}\\p{N}_.-]{1,253}")) {
                throw new IllegalArgumentException();
            }
            if (endpoint.length == 2) port = Integer.parseInt(endpoint[1]);
            if (port < 1024 || port > 65534) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            error("请输入 Mac 的 IPv4 地址或主机名，也可附加端口。");
            return;
        }
        if (account.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200) {
            error("macOS 账户名过长。");
            return;
        }
        int resolution = quality.getSelectedItemPosition();
        boolean useVirtual = virtualScreen.isChecked();
        boolean useAudio = audioOption.isChecked();
        boolean useDirectDecode = directDecode.isChecked();
        prefs.edit().putString("host", address).putString("user", account).putInt("quality", resolution)
                .putBoolean("virtualScreen", useVirtual).putBoolean("audio", useAudio)
                .putBoolean("directDecode", useDirectDecode).apply();
        password.getText().clear();
        hideIme();
        showSession(new RemoteSession.Destination(endpoint[0], port, account, useVirtual, useAudio, useDirectDecode),
                secret, RESOLUTIONS[resolution]);
    }

    private void showSession(RemoteSession.Destination destination, String password, int[] resolution) {
        int generation = ++viewGeneration;
        sessionScreen = true;
        videoConfigured = false;
        displayedFrame = false;
        muted = false;
        localMenuShortcut = false;
        captureRequested = false;
        audioUnavailable = !destination.audio();
        decoderName = "";
        lastFrames = lastBytes = lastStatisticsNs = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsController bars = getWindow().getInsetsController();
        bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsets.Type.systemBars());
        acquireWifiLock();

        screen = new FrameLayout(this);
        viewport = new RemoteViewport(this);
        viewport.frameSize(resolution[0], resolution[1]);
        screen.addView(viewport, new FrameLayout.LayoutParams(-1, -1));
        waiting = addWaitingPanel();
        controls = button("⋯", false);
        controls.setTextSize(22);
        controls.setContentDescription("远程会话菜单");
        controls.setAlpha(0.92f);
        controls.setVisibility(View.GONE);
        controls.setOnClickListener(v -> showControls());
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(dp(46), dp(42), Gravity.TOP | Gravity.END);
        corner.setMargins(dp(12), dp(9), dp(12), 0);
        screen.addView(controls, corner);
        stats = text("", 13, TEXT);
        stats.setBackground(shape(0xdb1a2330, 8));
        stats.setPadding(dp(10), dp(5), dp(10), dp(5));
        stats.setVisibility(View.GONE);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bottom.setMargins(dp(12), 0, 0, dp(12));
        screen.addView(stats, bottom);
        viewport.video().getHolder().addCallback(new SurfaceHolder.Callback() {
            private String secret = password;

            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (viewGeneration != generation || secret == null) return;
                Surface surface = holder.getSurface();
                try {
                    surface.setFrameRate(60, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // Surface remains usable if the platform declines the display hint.
                }
                session = new RemoteSession(surface, callbacks(generation), destination);
                viewport.attach(session);
                session.start(destination, secret, resolution[0], resolution[1]);
                secret = null;
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (viewGeneration == generation) endSession(null);
            }
        });
        setContentView(screen);
        viewport.requestFocus();
        main.postDelayed(() -> {
            if (viewGeneration == generation && !displayedFrame) {
                endSession(videoConfigured
                        ? "已连上 Mac，但等待 H.264 首帧超时。请重新连接。"
                        : "连接 Mac 超时。请检查屏幕共享设置和局域网连接。");
            }
        }, 48_000);
    }

    private View addWaitingPanel() {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setBackground(shape(PANEL, 18));
        box.setPadding(dp(28), dp(22), dp(28), dp(22));
        ProgressBar spinner = new ProgressBar(this);
        box.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        waitingLabel = text("正在连接 Mac…", 17, TEXT);
        waitingLabel.setGravity(Gravity.CENTER);
        add(box, waitingLabel, 12);
        Button cancel = button("取消", false);
        cancel.setOnClickListener(v -> endSession(null));
        add(box, cancel, 12);
        screen.addView(box, new FrameLayout.LayoutParams(dp(278), -2, Gravity.CENTER));
        return box;
    }

    private RemoteSession.Listener callbacks(int generation) {
        return new RemoteSession.Listener() {
            private boolean current() { return viewGeneration == generation; }

            @Override public void onConnected(int width, int height) {
                if (!current()) return;
                videoConfigured = true;
                viewport.remoteSize(width, height);
                if (!displayedFrame) waitingLabel.setText("正在等待 H.264 画面…");
            }

            @Override public void onDecoder(String name) {
                if (current()) decoderName = name;
            }

            @Override public void onFrameSize(int width, int height) {
                if (current()) viewport.frameSize(width, height);
            }

            @Override public void onFirstFrame() {
                if (!current()) return;
                displayedFrame = true;
                waiting.setVisibility(View.GONE);
                controls.setVisibility(View.VISIBLE);
                viewport.enableInput();
            }

            @Override public void onCursor(int width, int height, int x, int y, byte[] rgba) {
                if (current()) viewport.setHostCursor(width, height, x, y, rgba);
            }

            @Override public void onClipboardText(String text) {
                if (!current() || !hasWindowFocus() || text == null) return;
                clipboard().setPrimaryClip(ClipData.newPlainText("Mac", text));
                message("已复制 Mac 剪贴板");
            }

            @Override public void onStatistics(long frames, long losses, long bytes) {
                if (!current()) return;
                long now = System.nanoTime();
                if (lastStatisticsNs != 0 && stats.getVisibility() == View.VISIBLE) {
                    double dt = (now - lastStatisticsNs) / 1e9;
                    stats.setText(String.format(Locale.ROOT, "解码 %.0f fps  ·  %.1f Mb/s  ·  序号缺口 %d\n%s",
                            (frames - lastFrames) / dt, (bytes - lastBytes) * 8 / dt / 1e6, losses, decoderName));
                }
                lastStatisticsNs = now;
                lastFrames = frames;
                lastBytes = bytes;
            }

            @Override public void onAudioUnavailable() {
                if (current()) {
                    audioUnavailable = true;
                    message("当前 Android 音频解码器无法播放 Mac 声音");
                }
            }

            @Override public void onFailure(String reason) {
                if (current()) endSession(reason);
            }

            @Override public void onSessionEnded() {
                if (current()) endSession(null);
            }
        };
    }

    private void showControls() {
        if (!displayedFrame) return;
        viewport.clearInput();
        PopupMenu menu = new PopupMenu(this, controls, Gravity.END);
        menu.getMenu().add(0, 1, 0, "捕获触摸板指针");
        menu.getMenu().add(0, 2, 0, "打开屏幕键盘");
        menu.getMenu().add(0, 3, 0, "发送本机剪贴板");
        menu.getMenu().add(0, 4, 0, "复制 Mac 剪贴板");
        if (!audioUnavailable) menu.getMenu().add(0, 5, 0, muted ? "恢复声音" : "静音");
        menu.getMenu().add(0, 6, 0, stats.getVisibility() == View.VISIBLE ? "隐藏统计" : "显示统计");
        menu.getMenu().add(0, 7, 0, "断开连接");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1 -> captureRequested = true;
                case 2 -> {
                    viewport.requestFocus();
                    viewport.post(() -> getSystemService(InputMethodManager.class)
                            .showSoftInput(viewport, InputMethodManager.SHOW_IMPLICIT));
                }
                case 3 -> sendClipboard();
                case 4 -> session.clipboard(null);
                case 5 -> {
                    muted = !muted;
                    session.mute(muted);
                }
                case 6 -> stats.setVisibility(stats.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                case 7 -> endSession(null);
                default -> { return false; }
            }
            return true;
        });
        menu.setOnDismissListener(popup -> main.post(this::captureWhenFocused));
        menu.show();
    }

    private void sendClipboard() {
        ClipData clip = clipboard().getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            message("本机剪贴板没有文本");
            return;
        }
        String value = clip.getItemAt(0).getText().toString();
        if (value.length() > 65_536) {
            message("剪贴板文本过长");
            return;
        }
        session.clipboard(value);
    }

    private ClipboardManager clipboard() { return getSystemService(ClipboardManager.class); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!sessionScreen || viewport == null) return super.dispatchKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_F12) {
            if (event.getAction() == KeyEvent.ACTION_UP && localMenuShortcut) {
                localMenuShortcut = false;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed() && event.isAltPressed()) {
                if (event.getRepeatCount() == 0) {
                    localMenuShortcut = true;
                    if (viewport.hasPointerCapture()) viewport.clearInput();
                    else showControls();
                }
                return true;
            }
        }
        if (viewport.hasFocus() && viewport.sendKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (sessionScreen && viewport != null && !focused) viewport.clearInput();
        if (focused) captureWhenFocused();
    }

    private void captureWhenFocused() {
        if (!captureRequested || !sessionScreen || viewport == null || !hasWindowFocus()) return;
        captureRequested = false;
        viewport.capturePointer();
        message("Ctrl + Alt + F12 可释放指针");
    }

    @Override public void onBackPressed() {
        if (sessionScreen) {
            if (viewport.hasPointerCapture()) viewport.clearInput();
            else if (displayedFrame) showControls();
            else endSession(null);
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onStop() {
        if (discovery != null) discovery.close();
        if (sessionScreen) endSession(null);
        super.onStop();
    }

    private void endSession(String error) {
        if (!sessionScreen) return;
        ++viewGeneration;
        captureRequested = false;
        if (viewport != null) viewport.clearInput();
        if (session != null) session.cancel();
        session = null;
        viewport = null;
        if (wifi != null) {
            if (wifi.isHeld()) wifi.release();
            wifi = null;
        }
        showForm(error);
    }

    private void acquireWifiLock() {
        try {
            WifiManager manager = getApplicationContext().getSystemService(WifiManager.class);
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CloudBookRemote");
            wifi.acquire();
        } catch (RuntimeException ignored) {
            wifi = null;
        }
    }

    private void error(String value) {
        formError.setText(value);
        formError.setVisibility(value == null ? View.GONE : View.VISIBLE);
    }

    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void hideIme() {
        View focused = getCurrentFocus();
        if (focused != null) getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private EditText field(String label, String initial, int type) {
        EditText view = new EditText(this);
        view.setTextSize(15);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setHint(label);
        view.setSingleLine();
        view.setInputType(type);
        view.setText(initial);
        view.setPadding(dp(13), 0, dp(13), 0);
        view.setMinHeight(dp(42));
        view.setBackground(shape(FIELD, 10));
        return view;
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setButtonTintList(ColorStateList.valueOf(ACCENT));
        box.setTextColor(MUTED);
        box.setText(label);
        box.setChecked(checked);
        return box;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(primary ? Color.rgb(18, 37, 63) : TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : 0xff2d3d52));
        return button;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        return container;
    }

    private void add(LinearLayout container, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        container.addView(view, params);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
