// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** The Mac sends raw AAC-ELD/SBR access units. MediaCodec decodes into an AudioTrack. */
final class EldAudioOutput implements AutoCloseable {
    private static final byte[] ASC = {(byte) 0xf8, (byte) 0xe6, 0x51, 0x32, (byte) 0xe0, 0};
    private static final int MAX_PENDING = 8;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>(MAX_PENDING);
    private final ArrayDeque<Integer> slots = new ArrayDeque<>();
    private final HandlerThread worker = new HandlerThread("remote-audio", Process.THREAD_PRIORITY_AUDIO);
    private final Handler handler;
    private final Runnable unavailable;
    private volatile boolean closed;
    private volatile boolean muted;
    private MediaCodec codec;
    private AudioTrack track;
    private long pts;

    EldAudioOutput(Runnable unavailable) {
        this.unavailable = unavailable;
        worker.start();
        handler = new Handler(worker.getLooper());
        handler.post(this::startCodec);
    }

    synchronized void offer(byte[] raw) {
        if (closed || muted || raw.length == 0) return;
        if (pending.isEmpty()) handler.post(this::feedCodec);
        if (pending.size() == MAX_PENDING) pending.removeFirst();
        pending.addLast(raw);
    }

    synchronized void mute(boolean muted) {
        if (closed || this.muted == muted) return;
        this.muted = muted;
        pending.clear();
        handler.post(this::updateTrack);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        handler.post(this::release);
        worker.quitSafely();
    }

    private void startCodec() {
        if (closed) return;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(ASC));
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.setCallback(callback, handler);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception error) {
            fail(error);
        }
    }

    private void feedCodec() {
        try {
            while (!closed && !slots.isEmpty()) {
                byte[] unit;
                synchronized (this) {
                    unit = pending.pollFirst();
                }
                if (unit == null) return;
                int index = slots.removeFirst();
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < unit.length) {
                    throw new IllegalStateException("AAC-ELD access unit too large");
                }
                buffer.clear();
                buffer.put(unit);
                codec.queueInputBuffer(index, 0, unit.length, pts, 0);
                pts += 10_000;
            }
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override public void onInputBufferAvailable(MediaCodec source, int index) {
            if (closed) return;
            slots.addLast(index);
            feedCodec();
        }

        @Override public void onOutputBufferAvailable(MediaCodec source, int index,
                                                      MediaCodec.BufferInfo info) {
            if (closed) return;
            try {
                if (track != null && info.size > 0 && !muted) {
                    ByteBuffer pcm = source.getOutputBuffer(index);
                    if (pcm != null) track.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING);
                }
                source.releaseOutputBuffer(index, false);
            } catch (RuntimeException error) {
                fail(error);
            }
        }

        @Override public void onOutputFormatChanged(MediaCodec source, MediaFormat format) {
            if (closed) return;
            try {
                if (track != null) track.release();
                track = createTrack(format);
                if (!muted) track.play();
            } catch (RuntimeException error) {
                fail(error);
            }
        }

        @Override public void onError(MediaCodec source, MediaCodec.CodecException error) {
            fail(error);
        }
    };

    private void updateTrack() {
        if (closed || track == null) return;
        try {
            if (muted) {
                track.pause();
                track.flush();
            } else {
                track.play();
            }
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private void fail(Exception error) {
        if (closed) return;
        Log.w("CloudBookRemote", "AAC-ELD output unavailable", error);
        close();
        unavailable.run();
    }

    private void release() {
        try {
            if (codec != null) codec.release();
        } finally {
            if (track != null) track.release();
        }
    }

    private static AudioTrack createTrack(MediaFormat format) {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int speakers = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        int capacity = AudioTrack.getMinBufferSize(sampleRate, speakers, encoding);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(encoding)
                        .setSampleRate(sampleRate).setChannelMask(speakers).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(capacity)
                .build();
    }
}
