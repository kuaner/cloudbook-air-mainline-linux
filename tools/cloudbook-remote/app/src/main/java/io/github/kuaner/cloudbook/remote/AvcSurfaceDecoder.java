// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Trace;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Queues compressed access units and presents hardware-decoded frames. */
final class AvcSurfaceDecoder {
    private static final String TAG = "CloudBookRemote";
    interface Listener {
        void onDecoder(String name);
        void onFrameSize(int width, int height);
        void onFirstFrame();
        void onError(String message);
    }

    private record InputSlot(int index, ByteBuffer buffer) {}
    private record Unit(long timestamp, int size, boolean key, InputSlot slot, byte[] staged) {}
    private record Config(int width, int height, int fullRangeMatrix, byte[] sps, byte[] pps) {}
    private record Nv12Layout(Rect crop, int stride, int sliceHeight) {}

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String ROCKCHIP_AVC = "OMX.rk.video_decoder.avc";
    private static final String ROCKCHIP_CODEC2_AVC = "c2.rk.avc.decoder";
    // A repaired RTP gap can release several complete pictures at once.
    private static final int MAX_PENDING = 12;
    private static final int MAX_STARTUP_PENDING = 32;
    private static final int MAX_ACCESS_UNIT = 4 * 1024 * 1024;
    private static final int MAX_PENDING_BYTES = 2 * MAX_ACCESS_UNIT;
    private static final long RTP_CLOCK_HZ = 90_000;
    private static final long DIRECT_TARGET_LEAD_NS = 29_000_000L;
    private static final long DIRECT_MAX_LEAD_NS = 54_000_000L;
    private static final long DIRECT_MAX_LATE_NS = 14_000_000L;
    private static final long EXTERNAL_RELEASE_DELAY_MS = 4;
    private static final long VIDEO_IDLE_NS = 50_000_000L;

    private final Surface surface;
    private final Listener listener;
    private final boolean directDecode;
    private final HandlerThread worker = new HandlerThread("avc-surface", Process.THREAD_PRIORITY_DISPLAY);
    private final Handler handler;
    private final ArrayDeque<Unit> pending = new ArrayDeque<>();
    private final ArrayDeque<InputSlot> slots = new ArrayDeque<>();
    private final AtomicLong output = new AtomicLong();
    private final AtomicBoolean shown = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean needKeyframe = true;
    private Config config;
    private MediaCodec codec;
    private FullRangeFrames fullRangeOutput;
    private boolean disableExternalYuv;
    private Nv12Layout nv12;
    private boolean recoverDecoder = true;
    private boolean startingDecoder = true;
    private int pendingBytes;
    private boolean drainPosted;
    private boolean haveClock;
    private long previousRtp;
    private long elapsedTicks;
    private long lastDecodeRetryNs;
    private long directClockOffsetNs;
    private long previousOutputNs;
    private long previousPresentationUs;

    AvcSurfaceDecoder(Surface surface, Listener listener, boolean directDecode) {
        this.surface = surface;
        this.listener = listener;
        this.directDecode = directDecode;
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    synchronized void configure(int width, int height, int fullRangeMatrix, byte[] sps, byte[] pps) {
        if (closed) return;
        config = new Config(width, height, fullRangeMatrix, sps, pps);
        reset();
    }

    synchronized void reset() {
        if (closed) return;
        needKeyframe = true;
        recoverDecoder = true;
        startingDecoder = true;
        clearPending();
        scheduleDrain();
    }

    // The native view is valid only for this callback. Pending units own their data or a filled codec slot.
    synchronized boolean offer(long timestamp, ByteBuffer data, boolean keyframe) {
        int size = data.remaining();
        if (closed || config == null || size == 0 || size > MAX_ACCESS_UNIT) {
            return false;
        }
        int limit = startingDecoder ? MAX_STARTUP_PENDING : MAX_PENDING;
        if (pending.size() >= limit || pendingBytes + size > MAX_PENDING_BYTES) {
            Log.w(TAG, "AVC decoder input queue full, waiting for a fresh keyframe");
            clearPending();
            needKeyframe = true;
        }
        if (needKeyframe && !keyframe) return false;
        needKeyframe = false;
        Trace.setCounter("CloudBook received RTP timestamp", timestamp);
        InputSlot slot = pending.isEmpty() && !recoverDecoder ? slots.peekFirst() : null;
        if (slot != null && size <= slot.buffer.capacity()) {
            Trace.beginSection("CloudBook AVC input direct");
            try {
                slot.buffer.clear();
                slot.buffer.put(data);
                slots.removeFirst();
            } finally {
                Trace.endSection();
            }
            pending.addLast(new Unit(timestamp, size, keyframe, slot, null));
        } else {
            byte[] staging = new byte[size];
            data.get(staging);
            pending.addLast(new Unit(timestamp, size, keyframe, null, staging));
        }
        pendingBytes += size;
        scheduleDrain();
        return true;
    }

    private void clearPending() {
        Unit unit;
        while ((unit = pending.pollFirst()) != null) {
            if (unit.slot != null) slots.addFirst(unit.slot);
        }
        pendingBytes = 0;
    }

    private void scheduleDrain() {
        if (!drainPosted) {
            drainPosted = true;
            handler.post(this::drain);
        }
    }

    boolean needsKeyframe() { return needKeyframe; }
    long outputFrames() { return output.get(); }

    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            clearPending();
        }
        handler.post(this::releaseCodec);
        worker.quitSafely();
    }

    private void drain() {
        Config cfg;
        boolean reset;
        synchronized (this) {
            drainPosted = false;
            if (closed) return;
            cfg = config;
            reset = recoverDecoder;
            if (reset) slots.clear();
            recoverDecoder = false;
        }
        if (cfg == null) return;
        if (reset) releaseCodec();
        synchronized (this) {
            if (closed || recoverDecoder) return;
        }
        try {
            if (codec == null) startCodec(cfg);
            while (true) {
                Unit unit;
                InputSlot slot;
                synchronized (this) {
                    if (closed || recoverDecoder) return;
                    unit = pending.peekFirst();
                    if (unit == null) {
                        if (!slots.isEmpty()) startingDecoder = false;
                        return;
                    }
                    slot = unit.slot != null ? unit.slot : slots.pollFirst();
                    if (slot == null) return;
                    pending.removeFirst();
                    pendingBytes -= unit.size;
                }
                if (unit.size > slot.buffer.capacity()) {
                    Log.w(TAG, "AVC access unit exceeds codec input buffer");
                    reset();
                    return;
                }
                long pts = unwrapTimestamp(unit.timestamp);
                Trace.beginSection("CloudBook AVC submit");
                try {
                    Trace.setCounter("CloudBook submitted RTP timestamp", unit.timestamp);
                    Trace.setCounter("CloudBook decoder PTS us", pts);
                    if (unit.staged != null) {
                        slot.buffer.clear();
                        slot.buffer.put(unit.staged);
                    }
                    codec.queueInputBuffer(slot.index, 0, unit.size, pts,
                            unit.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                } finally {
                    Trace.endSection();
                }
            }
        } catch (IOException error) {
            listener.onError("无法启动 H.264 硬件解码器。" );
            close();
        } catch (RuntimeException error) {
            handleCodecError(error);
        }
    }

    private void startCodec(Config cfg) throws IOException {
        for (MediaCodecInfo info : candidates()) {
            if (startCodec(cfg, info)) return;
        }
        throw new IOException("No working hardware AVC decoder");
    }

    private boolean startCodec(Config cfg, MediaCodecInfo info) {
        boolean retryUpload;
        do {
            MediaCodec selected = null;
            FullRangeFrames rangeOutput = null;
            try {
                selected = MediaCodec.createByCodecName(info.getName());
                MediaFormat format = MediaFormat.createVideoFormat(MIME, cfg.width, cfg.height);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(cfg.sps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(cfg.pps));
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 60);
                boolean convertFullRange = !directDecode && cfg.fullRangeMatrix != 0
                        && isRockchipAvc(info.getName());
                if (convertFullRange) rangeOutput = new FullRangeFrames(selected, cfg.fullRangeMatrix);
                Surface decoderSurface = convertFullRange ? rangeOutput.openDecoderSurface() : surface;
                if (decoderSurface == null) {
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                }
                if (info.getCapabilitiesForType(MIME).isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }
                selected.setCallback(callback, handler);
                selected.configure(format, decoderSurface, null, 0);
                selected.start();
                fullRangeOutput = rangeOutput;
                codec = selected;
                if (convertFullRange) Log.i(TAG, "GPU conversion for full-range AVC, matrix="
                        + cfg.fullRangeMatrix + (decoderSurface == null ? " (upload)" : " (external YUV)"));
                else if (directDecode && cfg.fullRangeMatrix != 0) Log.i(TAG, "Direct Surface output for full-range AVC");
                listener.onDecoder(info.getName());
                return true;
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Cannot configure " + info.getName(), error);
                retryUpload = rangeOutput != null && rangeOutput.external;
                if (retryUpload) disableExternalYuv = true;
                try {
                    if (selected != null) selected.release();
                } finally {
                    if (rangeOutput != null) rangeOutput.close();
                }
            }
        } while (retryUpload);
        return false;
    }

    private static List<MediaCodecInfo> candidates() {
        List<MediaCodecInfo> matches = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || !info.isHardwareAccelerated() || info.isAlias()) continue;
            for (String mime : info.getSupportedTypes()) {
                if (MIME.equalsIgnoreCase(mime)) matches.add(info);
            }
        }
        matches.sort(Comparator.comparingInt(info -> isRockchipAvc(info.getName()) ? 0 : 1));
        return matches;
    }

    private static boolean isRockchipAvc(String name) {
        return ROCKCHIP_AVC.equals(name) || ROCKCHIP_CODEC2_AVC.equals(name);
    }

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override public void onInputBufferAvailable(MediaCodec source, int index) {
            if (source != codec || closed) return;
            try {
                ByteBuffer buffer = source.getInputBuffer(index);
                if (buffer == null) throw new IllegalStateException("Codec returned no AVC input buffer");
                synchronized (AvcSurfaceDecoder.this) {
                    slots.addLast(new InputSlot(index, buffer));
                }
                drain();
            } catch (RuntimeException error) {
                handleCodecError(error);
            }
        }

        @Override public void onOutputBufferAvailable(MediaCodec source, int index,
                                                     MediaCodec.BufferInfo info) {
            if (source != codec || closed) return;
            Trace.beginSection("CloudBook AVC output");
            try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    Trace.setCounter("CloudBook decoded PTS us", info.presentationTimeUs);
                    long now = System.nanoTime();
                    boolean resumeFromIdle = followsIdle(now, info.presentationTimeUs);
                    if (fullRangeOutput != null) {
                        fullRangeOutput.offer(index, nv12, info.presentationTimeUs, resumeFromIdle);
                    } else {
                        renderDirect(source, index, info.presentationTimeUs, now, resumeFromIdle);
                        framePresented(source);
                    }
                    output.incrementAndGet();
                } else {
                    source.releaseOutputBuffer(index, false);
                }
            } catch (RuntimeException error) {
                handleCodecError(error);
            } finally {
                Trace.endSection();
            }
        }

        @Override public void onOutputFormatChanged(MediaCodec source, MediaFormat format) {
            if (source != codec || closed) return;
            Log.i(TAG, "AVC output format " + format);
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            Rect crop = new Rect(format.getInteger("crop-left", 0), format.getInteger("crop-top", 0),
                    format.getInteger("crop-right", width - 1) + 1,
                    format.getInteger("crop-bottom", height - 1) + 1);
            nv12 = null;
            if (fullRangeOutput != null && format.getInteger(MediaFormat.KEY_COLOR_FORMAT, 0)
                    == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                nv12 = new Nv12Layout(crop, format.getInteger(MediaFormat.KEY_STRIDE, width),
                        format.getInteger(MediaFormat.KEY_SLICE_HEIGHT, height));
            }
            listener.onFrameSize(crop.width(), crop.height());
        }

        @Override public void onError(MediaCodec source, MediaCodec.CodecException error) {
            if (source == codec && !closed) handleCodecError(error);
        }
    };

    private boolean followsIdle(long now, long presentationTimeUs) {
        // A late callback is not itself an idle stream. The source clock must also have a gap.
        boolean idle = previousOutputNs == 0 || (now - previousOutputNs >= VIDEO_IDLE_NS
                && presentationTimeUs - previousPresentationUs >= VIDEO_IDLE_NS / 1_000L);
        previousOutputNs = now;
        previousPresentationUs = presentationTimeUs;
        return idle;
    }

    private void renderDirect(MediaCodec source, int index, long presentationTimeUs,
                              long now, boolean resumeFromIdle) {
        if (!directDecode) {
            source.releaseOutputBuffer(index, true);
            return;
        }
        // Keep the decoder's cadence on the Surface clock, without re-anchoring for ordinary decoder bursts.
        long streamNs = presentationTimeUs * 1_000L;
        long targetNs = streamNs + directClockOffsetNs;
        if (targetNs < now - DIRECT_MAX_LATE_NS || targetNs > now + DIRECT_MAX_LEAD_NS) {
            targetNs = now + DIRECT_TARGET_LEAD_NS;
            directClockOffsetNs = targetNs - streamNs;
        }
        targetNs = resumeFromIdle ? now : Math.max(now, targetNs);
        Trace.setCounter("CloudBook displayed PTS us", presentationTimeUs);
        Trace.setCounter("CloudBook desired present ns", targetNs);
        source.releaseOutputBuffer(index, targetNs);
    }

    private void framePresented(MediaCodec source) {
        if (shown.compareAndSet(false, true)) {
            Log.i(TAG, "First AVC frame sent to the Surface by " + source.getName());
            listener.onFirstFrame();
        }
    }

    private final class FullRangeFrames implements AutoCloseable {
        private record Frame(int index, Nv12Layout layout, long presentationTimeUs) {}

        private final MediaCodec decoder;
        private final int matrix;
        private final HandlerThread worker = new HandlerThread("avc-render", Process.THREAD_PRIORITY_DISPLAY);
        private final Handler render;
        private FullRangeVideoOutput renderer;
        private boolean external;
        private Choreographer vsync;
        private final Choreographer.FrameCallback frameCallback = this::scheduleExternalFrame;
        private final ArrayDeque<Integer> decoded = new ArrayDeque<>(2);
        private boolean releaseScheduled;
        private boolean externalFailed;
        private Frame next;
        private boolean posted;
        private volatile boolean closing;

        FullRangeFrames(MediaCodec decoder, int matrix) {
            this.decoder = decoder;
            this.matrix = matrix;
            worker.start();
            render = new Handler(worker.getLooper());
        }

        Surface openDecoderSurface() {
            if (disableExternalYuv) return null;
            FutureTask<Surface> init = new FutureTask<>(() -> {
                FullRangeVideoOutput output = new FullRangeVideoOutput(surface, matrix, true);
                try {
                    vsync = Choreographer.getInstance();
                    output.decoderTexture().setOnFrameAvailableListener(texture -> drawExternal(), render);
                    renderer = output;
                    return output.decoderSurface();
                } catch (RuntimeException error) {
                    output.close();
                    throw error;
                }
            });
            render.post(init);
            try {
                Surface result = init.get();
                external = true;
                return result;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted setting up decoder surface", error);
            } catch (ExecutionException error) {
                Log.i(TAG, "External YUV unavailable", error.getCause());
                disableExternalYuv = true;
                return null;
            }
        }

        private void drawExternal() {
            if (closing || externalFailed) return;
            Trace.beginSection("CloudBook AVC render");
            try {
                if (renderer.renderExternal()) framePresented(decoder);
            } catch (RuntimeException error) {
                fallBackToUpload(error);
            } finally {
                Trace.endSection();
            }
        }

        void offer(int index, Nv12Layout layout, long presentationTimeUs, boolean resumeFromIdle) {
            if (external) {
                offerExternal(index, resumeFromIdle);
                return;
            }
            Frame superseded;
            synchronized (this) {
                superseded = next;
                next = new Frame(index, layout, presentationTimeUs);
                if (!posted) {
                    posted = true;
                    render.post(this::draw);
                }
            }
            // MediaCodec can continue decoding while the GPU presents. If
            // it gets ahead, render the most recent decoded picture.
            if (superseded != null) decoder.releaseOutputBuffer(superseded.index, false);
        }

        private void offerExternal(int index, boolean resumeFromIdle) {
            Integer superseded = null;
            boolean immediate;
            synchronized (this) {
                immediate = resumeFromIdle && !releaseScheduled;
                if (!immediate) {
                    if (!releaseScheduled) {
                        releaseScheduled = true;
                        render.post(() -> vsync.postFrameCallback(frameCallback));
                    }
                    // A short queue smooths bursty codec output without accumulating a long delay.
                    if (decoded.size() == 2) superseded = decoded.removeFirst();
                    decoded.addLast(index);
                }
            }
            if (immediate) {
                // After an idle interval, send the first picture without waiting for the pacing clock.
                decoder.releaseOutputBuffer(index, true);
            } else if (superseded != null) {
                decoder.releaseOutputBuffer(superseded, false);
            }
        }

        private void scheduleExternalFrame(long frameTimeNanos) {
            // Give the decoder a few more milliseconds to finish the next picture while
            // still leaving time for the GPU to submit ahead of the next compositor latch.
            long releaseUptimeMs = frameTimeNanos / 1_000_000L + EXTERNAL_RELEASE_DELAY_MS;
            render.postAtTime(this::releaseExternalFrame, releaseUptimeMs);
        }

        private void releaseExternalFrame() {
            int index;
            synchronized (this) {
                if (closing || externalFailed || decoded.isEmpty()) {
                    releaseScheduled = false;
                    return;
                }
                index = decoded.removeFirst();
                // Keep the phase through an empty queue if the next picture is still being decoded.
                vsync.postFrameCallback(frameCallback);
            }
            try {
                decoder.releaseOutputBuffer(index, true);
            } catch (RuntimeException error) {
                fallBackToUpload(error);
            }
        }

        private void fallBackToUpload(RuntimeException error) {
            externalFailed = true;
            handler.post(() -> {
                if (closed || fullRangeOutput != this) return;
                Log.w(TAG, "External YUV output failed, switching to texture upload", error);
                disableExternalYuv = true;
                reset();
            });
        }

        private void draw() {
            Frame frame;
            synchronized (this) {
                posted = false;
                if (closing) return;
                frame = next;
                next = null;
            }
            if (frame == null) return;
            Trace.beginSection("CloudBook AVC render");
            try {
                try {
                    if (renderer == null) renderer = new FullRangeVideoOutput(surface, matrix, false);
                    if (frame.layout != null) {
                        Nv12Layout layout = frame.layout;
                        renderer.renderNv12(decoder.getOutputBuffer(frame.index),
                                layout.crop, layout.stride, layout.sliceHeight, frame.presentationTimeUs);
                    } else {
                        try (Image image = decoder.getOutputImage(frame.index)) {
                            if (image == null) throw new IllegalStateException("Codec returned no YUV image");
                            renderer.render(image, frame.presentationTimeUs);
                        }
                    }
                    framePresented(decoder);
                } finally {
                    decoder.releaseOutputBuffer(frame.index, false);
                }
            } catch (RuntimeException error) {
                handler.post(() -> {
                    if (!closed && fullRangeOutput == this) handleCodecError(error);
                });
            } finally {
                Trace.endSection();
            }
        }

        @Override public void close() {
            Frame abandoned;
            synchronized (this) {
                closing = true;
                abandoned = next;
                next = null;
            }
            CountDownLatch released = new CountDownLatch(1);
            render.post(() -> {
                try {
                    if (vsync != null) vsync.removeFrameCallback(frameCallback);
                    for (int index : decoded) decoder.releaseOutputBuffer(index, false);
                    decoded.clear();
                    if (abandoned != null) decoder.releaseOutputBuffer(abandoned.index, false);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not release pending AVC frame", error);
                } finally {
                    try {
                        if (renderer != null) renderer.close();
                    } finally {
                        released.countDown();
                    }
                }
            });
            worker.quitSafely();
            boolean interrupted = false;
            while (true) {
                try {
                    released.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private long unwrapTimestamp(long timestamp) {
        if (!haveClock) {
            previousRtp = timestamp;
            elapsedTicks = 0;
            haveClock = true;
        } else {
            elapsedTicks += (int) (timestamp - previousRtp);
            previousRtp = timestamp;
        }
        return elapsedTicks * 1_000_000L / RTP_CLOCK_HZ;
    }

    private void handleCodecError(RuntimeException error) {
        long now = System.nanoTime();
        Log.w(TAG, "MediaCodec AVC error", error);
        if (lastDecodeRetryNs != 0 && now - lastDecodeRetryNs < 2_000_000_000L) {
            listener.onError("H.264 硬件解码器连续出错，请重新连接。" );
            close();
            return;
        }
        lastDecodeRetryNs = now;
        reset();
    }

    private void releaseCodec() {
        synchronized (this) {
            slots.clear();
        }
        nv12 = null;
        haveClock = false;
        directClockOffsetNs = 0;
        previousOutputNs = 0;
        if (fullRangeOutput != null) {
            fullRangeOutput.close();
            fullRangeOutput = null;
        }
        if (codec == null) return;
        MediaCodec old = codec;
        codec = null;
        old.release();
    }
}
