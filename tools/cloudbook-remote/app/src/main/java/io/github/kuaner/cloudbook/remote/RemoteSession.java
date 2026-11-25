// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** JNI-facing session and ordered input queue. Only this class crosses into the Rust transport. */
public final class RemoteSession implements AvcSurfaceDecoder.Listener {
    static {
        System.loadLibrary("cloudbook_remote");
    }

    interface Listener {
        void onConnected(int width, int height);
        void onDecoder(String name);
        void onFrameSize(int width, int height);
        void onFirstFrame();
        void onCursor(int width, int height, int x, int y, byte[] rgba);
        void onClipboardText(String text);
        void onStatistics(long frames, long losses, long bytes);
        void onAudioUnavailable();
        void onFailure(String message);
        void onSessionEnded();
    }

    record Destination(String host, int port, String user, boolean virtualScreen, boolean audio,
                       boolean directDecode) {}

    private static final int MOVE = 1;
    private static final int BUTTON = 2;
    private static final int SCROLL = 3;
    private static final int KEY = 4;
    private static final int RELEASE = 5;
    private static final int SEND_TEXT = 6;
    private static final int GET_TEXT = 7;
    private static final int INPUT_HEADER_BYTES = 2 + 4 * Integer.BYTES + 2 * Float.BYTES + Long.BYTES;

    private record Input(int kind, int x, int y, int buttons, int value, boolean down,
                         String text, float deltaX, float deltaY, long queuedNs) {
        Input(int kind, int x, int y, int buttons, int value, boolean down, String text) {
            this(kind, x, y, buttons, value, down, text, 0, 0);
        }

        Input(int kind, int x, int y, int buttons, int value, boolean down, String text,
              float deltaX, float deltaY) {
            this(kind, x, y, buttons, value, down, text, deltaX, deltaY, System.nanoTime());
        }

        byte[] pack() {
            // Match the input layout read by control::Inputs in the Rust transport.
            byte[] utf8 = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
            ByteBuffer wire = ByteBuffer.allocate(INPUT_HEADER_BYTES + (utf8 == null ? 0 : utf8.length));
            wire.put((byte) kind).putInt(x).putInt(y).putInt(buttons).putInt(value)
                    .put((byte) (down ? 1 : 0)).putFloat(deltaX).putFloat(deltaY).putLong(queuedNs);
            if (utf8 != null) wire.put(utf8);
            return wire.array();
        }
    }

    // The native protocol holds the two negotiated UDP ports for the session.
    private static final Object TRANSPORT = new Object();
    private static final int MAX_INPUT_QUEUE = 160;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Input> events = new ArrayDeque<>();
    private final AvcSurfaceDecoder video;
    private final EldAudioOutput audio;
    private volatile boolean cancelled;

    RemoteSession(Surface surface, Listener listener, Destination destination) {
        this.listener = listener;
        video = new AvcSurfaceDecoder(surface, this, destination.directDecode());
        audio = destination.audio() ? new EldAudioOutput(() -> notifyUi(listener::onAudioUnavailable)) : null;
    }

    void start(Destination destination, String password, int width, int height) {
        new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                synchronized (TRANSPORT) {
                    if (cancelled) return;
                    runNative(
                            destination.host(), destination.port(), destination.user(), password,
                            width, height, destination.virtualScreen(), destination.audio());
                }
            } catch (RuntimeException error) {
                Log.w("CloudBookRemote", "Could not start session", error);
                onFailure("无法启动会话，请重新连接。");
            } finally {
                cancel();
                main.post(listener::onSessionEnded);
            }
        }, "remote-session").start();
    }

    void cancel() {
        synchronized (events) {
            if (cancelled) return;
            cancelled = true;
            events.clear();
            events.notifyAll();
        }
        video.close();
        if (audio != null) audio.close();
    }

    void mute(boolean muted) {
        if (audio != null) audio.mute(muted);
    }

    void pointer(boolean buttonChange, int x, int y, int buttons) {
        enqueue(new Input(buttonChange ? BUTTON : MOVE, x, y, buttons, 0, false, null));
    }

    void scroll(int x, int y, int buttons, float horizontal, float vertical) {
        enqueue(new Input(SCROLL, x, y, buttons, 0, false, null, horizontal, vertical));
    }

    void key(int keysym, boolean down) {
        enqueue(new Input(KEY, 0, 0, 0, keysym, down, null));
    }

    void releaseInput() {
        enqueue(new Input(RELEASE, 0, 0, 0, 0, false, null));
    }

    void clipboard(String text) {
        enqueue(new Input(text == null ? GET_TEXT : SEND_TEXT, 0, 0, 0, 0, false, text));
    }

    private void enqueue(Input event) {
        boolean overflow = false;
        synchronized (events) {
            if (cancelled) return;
            if (event.kind == MOVE && !events.isEmpty() && events.peekLast().kind == MOVE) {
                events.removeLast();
            }
            if (events.size() >= MAX_INPUT_QUEUE) {
                events.removeIf(input -> input.kind == MOVE);
                if (events.size() >= MAX_INPUT_QUEUE) {
                    if (event.kind == MOVE) return;
                    overflow = true;
                }
            }
            if (!overflow) {
                events.addLast(event);
                events.notifyAll();
            }
        }
        if (overflow) {
            onFailure("网络拥塞，无法及时发送输入。请重新连接。");
            cancel();
        }
    }

    // Session callbacks used by the native transport.
    public boolean isCancelled() { return cancelled; }
    public boolean needsKeyframe() { return video.needsKeyframe(); }
    public long outputFrames() { return video.outputFrames(); }
    private native void runNative(String host, int port, String user, String password,
                                  int width, int height, boolean virtualScreen, boolean audio);

    public byte[] pollInput(int timeoutMs) throws InterruptedException {
        Input event;
        synchronized (events) {
            if (events.isEmpty() && !cancelled) events.wait(timeoutMs);
            event = events.pollFirst();
        }
        return event == null ? null : event.pack();
    }

    public void onVideoFormat(int width, int height, int fullRangeMatrix, byte[] sps, byte[] pps) {
        video.configure(width, height, fullRangeMatrix, sps, pps);
        notifyUi(() -> listener.onConnected(width, height));
    }

    private boolean onVideoUnit(long timestamp, ByteBuffer data, boolean keyframe) {
        return !cancelled && video.offer(timestamp, data, keyframe);
    }

    private void onAudioUnit(byte[] data) {
        if (!cancelled && audio != null) audio.offer(data);
    }

    public void onConnected(int width, int height) {
        notifyUi(() -> listener.onConnected(width, height));
    }

    @Override public void onDecoder(String name) { notifyUi(() -> listener.onDecoder(name)); }
    @Override public void onFirstFrame() { notifyUi(listener::onFirstFrame); }
    @Override public void onError(String message) {
        onFailure(message);
        cancel();
    }

    @Override public void onFrameSize(int width, int height) {
        notifyUi(() -> listener.onFrameSize(width, height));
    }

    public void onCursor(int width, int height, int x, int y, byte[] rgba) {
        notifyUi(() -> listener.onCursor(width, height, x, y, rgba));
    }

    public void onClipboardText(String text) { notifyUi(() -> listener.onClipboardText(text)); }

    public void onStatistics(long losses, long bytes) {
        long frames = video.outputFrames();
        notifyUi(() -> listener.onStatistics(frames, losses, bytes));
    }

    public void onFailure(String message) {
        if (!cancelled) main.post(() -> listener.onFailure(message));
    }

    private void notifyUi(Runnable message) {
        if (!cancelled) main.post(() -> { if (!cancelled) message.run(); });
    }
}
