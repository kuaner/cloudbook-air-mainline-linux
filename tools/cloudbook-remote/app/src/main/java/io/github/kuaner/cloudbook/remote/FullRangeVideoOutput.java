// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Trace;
import android.view.Surface;

import java.nio.ByteBuffer;

/** Converts full-range AVC to RGB before Rockchip's limited-range YUV compositor. */
final class FullRangeVideoOutput implements AutoCloseable {
    private static final String VERTEX = """
            #version 300 es
            out vec2 uv;
            uniform mat4 textureTransform;
            void main() {
                vec2 pos = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                uv = (textureTransform * vec4(pos, 0.0, 1.0)).xy;
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String YUV_TO_RGB = """
            precision highp float;
            in vec2 uv;
            out vec4 color;
            uniform vec4 coefficients;

            vec4 rgb(vec3 yuv) {
                float y = yuv.x;
                float u = yuv.y - 128.0 / 255.0;
                float v = yuv.z - 128.0 / 255.0;
                return vec4(y + coefficients.x * v,
                            y - coefficients.y * u - coefficients.z * v,
                            y + coefficients.w * u, 1.0);
            }
            """;
    private static final String FRAGMENT = "#version 300 es\n" + YUV_TO_RGB + """
            uniform sampler2D luma;
            uniform sampler2D chroma;
            uniform sampler2D cb;
            uniform sampler2D cr;
            uniform bool semiplanar;

            void main() {
                vec2 uvColor = semiplanar ? texture(chroma, uv).rg
                        : vec2(texture(cb, uv).r, texture(cr, uv).r);
                color = rgb(vec3(texture(luma, uv).r, uvColor));
            }
            """;
    private static final String EXTERNAL_FRAGMENT = """
            #version 300 es
            #extension GL_EXT_YUV_target : require
            """ + YUV_TO_RGB + """
            uniform __samplerExternal2DY2YEXT decoderFrame;
            void main() {
                color = rgb(texture(decoderFrame, uv).xyz);
            }
            """;

    private final EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private final int[] textures = new int[4];
    private final int[] widths = new int[4];
    private final int[] heights = new int[4];
    private final int[] viewSize = new int[2];
    private final float[] transform = new float[16];
    private int semiplanarUniform;
    private int transformUniform;
    private SurfaceTexture decoderTexture;
    private Surface decoderSurface;
    private long lastTimestamp = Long.MIN_VALUE;

    FullRangeVideoOutput(Surface surface, int matrix, boolean external) {
        try {
            initialize(surface, matrix, external);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    private void initialize(Surface surface, int matrix, boolean external) {
        if (!EGL14.eglInitialize(display, null, 0, null, 0)) {
            throw new IllegalStateException("Cannot initialize EGL");
        }
        int[] attributes = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0);
        if (count[0] == 0) throw new IllegalStateException("OpenGL ES 3 window unavailable");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        window = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            throw new IllegalStateException("Cannot attach EGL to video surface");
        }
        EGL14.eglSwapInterval(display, 0);

        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, VERTEX));
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, external ? EXTERNAL_FRAGMENT : FRAGMENT));
        GLES30.glLinkProgram(program);
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, count, 0);
        if (count[0] == 0) throw new IllegalStateException(GLES30.glGetProgramInfoLog(program));
        GLES30.glUseProgram(program);
        transformUniform = GLES30.glGetUniformLocation(program, "textureTransform");
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "luma"), 0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "chroma"), 1);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "cb"), 2);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "cr"), 3);
        semiplanarUniform = GLES30.glGetUniformLocation(program, "semiplanar");
        float kr = switch (matrix) { case 5, 6 -> 0.299f; case 9 -> 0.2627f; default -> 0.2126f; };
        float kb = switch (matrix) { case 5, 6 -> 0.114f; case 9 -> 0.0593f; default -> 0.0722f; };
        float kg = 1 - kr - kb;
        float red = 2 * (1 - kr);
        float blue = 2 * (1 - kb);
        GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "coefficients"),
                red, kb * blue / kg, kr * red / kg, blue);
        GLES30.glGenTextures(textures.length, textures, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        if (external) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            decoderTexture = new SurfaceTexture(textures[0]);
            decoderSurface = new Surface(decoderTexture);
        } else {
            // Byte buffers store the top row first. SurfaceTexture supplies its own transform.
            transform[0] = transform[10] = transform[13] = transform[15] = 1.0f;
            transform[5] = -1.0f;
            GLES30.glUniformMatrix4fv(transformUniform, 1, false, transform, 0);
        }
    }

    Surface decoderSurface() { return decoderSurface; }

    SurfaceTexture decoderTexture() { return decoderTexture; }

    boolean renderExternal() {
        Trace.beginSection("CloudBook YUV acquire");
        try {
            decoderTexture.updateTexImage();
        } finally {
            Trace.endSection();
        }
        long timestamp = decoderTexture.getTimestamp();
        if (timestamp == lastTimestamp) return false;
        lastTimestamp = timestamp;
        decoderTexture.getTransformMatrix(transform);
        GLES30.glUniformMatrix4fv(transformUniform, 1, false, transform, 0);
        present(false, timestamp / 1_000);
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            throw new IllegalStateException("Cannot sample decoder YUV texture");
        }
        return true;
    }

    void render(Image image, long presentationTimeUs) {
        Rect crop = image.getCropRect();
        Image.Plane[] planes = image.getPlanes();
        uploadPlane(0, planes[0], crop.left, crop.top, crop.width(), crop.height());
        int chromaWidth = (crop.width() + 1) / 2;
        int chromaHeight = (crop.height() + 1) / 2;
        uploadPlane(2, planes[1], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight);
        uploadPlane(3, planes[2], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight);
        present(false, presentationTimeUs);
    }

    void renderNv12(ByteBuffer data, Rect crop, int stride, int sliceHeight, long presentationTimeUs) {
        int base = data.position();
        data.position(base + crop.top * stride + crop.left);
        upload(0, data, stride, crop.width(), crop.height(), GLES30.GL_R8, GLES30.GL_RED);
        data.position(base + sliceHeight * stride + (crop.top / 2) * stride + (crop.left & ~1));
        upload(1, data, stride / 2, (crop.width() + 1) / 2, (crop.height() + 1) / 2,
                GLES30.GL_RG8, GLES30.GL_RG);
        present(true, presentationTimeUs);
    }

    private void present(boolean semiplanar, long presentationTimeUs) {
        GLES30.glUniform1i(semiplanarUniform, semiplanar ? 1 : 0);
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, viewSize, 0);
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, viewSize, 1);
        GLES30.glViewport(0, 0, viewSize[0], viewSize[1]);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        long desiredPresentNs = System.nanoTime();
        EGLExt.eglPresentationTimeANDROID(display, window, desiredPresentNs);
        Trace.setCounter("CloudBook displayed PTS us", presentationTimeUs);
        Trace.setCounter("CloudBook desired present ns", desiredPresentNs);
        Trace.beginSection("CloudBook EGL swap");
        try {
            if (!EGL14.eglSwapBuffers(display, window)) {
                throw new IllegalStateException("Cannot present full-range frame");
            }
        } finally {
            Trace.endSection();
        }
    }

    private void uploadPlane(int index, Image.Plane plane, int left, int top, int width, int height) {
        int rowStride = plane.getRowStride();
        if (plane.getPixelStride() != 1) throw new IllegalStateException("Unsupported planar AVC output");
        ByteBuffer data = plane.getBuffer();
        data.position(data.position() + top * rowStride + left);
        upload(index, data, rowStride, width, height, GLES30.GL_R8, GLES30.GL_RED);
    }

    private void upload(int index, ByteBuffer data, int rowStride, int width, int height,
                        int internalFormat, int externalFormat) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[index]);
        if (widths[index] != width || heights[index] != height) {
            widths[index] = width;
            heights[index] = height;
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                    externalFormat, GLES30.GL_UNSIGNED_BYTE, null);
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowStride);
        Trace.beginSection(index == 0 ? "CloudBook Y upload" : "CloudBook UV upload");
        try {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    externalFormat, GLES30.GL_UNSIGNED_BYTE, data);
        } finally {
            Trace.endSection();
        }
    }

    private static int compile(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES30.glGetShaderInfoLog(shader));
        return shader;
    }

    @Override public void close() {
        if (decoderSurface != null) decoderSurface.release();
        if (decoderTexture != null) decoderTexture.release();
        if (display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
        EGL14.eglReleaseThread();
    }
}
