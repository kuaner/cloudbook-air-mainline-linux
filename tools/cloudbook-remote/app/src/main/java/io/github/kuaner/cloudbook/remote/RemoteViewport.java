// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;

/** Aspect-fit video and the input area for both Android mouse modes. */
final class RemoteViewport extends FrameLayout {
    private static final float CLOUDBOOK_TOUCHPAD_VERTICAL_SCROLL_SCALE = 3.0f;

    private final SurfaceView video;
    private final CursorOverlay cursor;
    private final Rect picture = new Rect();
    private final Map<Long, Integer> heldKeys = new HashMap<>();
    private RemoteSession session;
    private int frameWidth = 1920;
    private int frameHeight = 1200;
    private int remoteWidth = 1920;
    private int remoteHeight = 1200;
    private float pointerX;
    private float pointerY;
    private int buttons;
    private boolean touchpadSecondary;
    private boolean positioned;
    private boolean inputEnabled;
    private boolean touchDrag;
    private PointerIcon hostIcon;

    RemoteViewport(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setFocusableInTouchMode(true);
        video = new SurfaceView(context);
        video.setFocusable(false);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cursor = new CursorOverlay(context);
        addView(cursor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    SurfaceView video() { return video; }

    void attach(RemoteSession remote) {
        session = remote;
        inputEnabled = false;
    }

    void enableInput() {
        inputEnabled = true;
        requestFocus();
    }

    void remoteSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        remoteWidth = width;
        remoteHeight = height;
    }

    void frameSize(int width, int height) {
        if (width <= 0 || height <= 0 || (frameWidth == width && frameHeight == height)) return;
        frameWidth = width;
        frameHeight = height;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        double scale = Math.min((double) width / frameWidth, (double) height / frameHeight);
        LayoutParams params = (LayoutParams) video.getLayoutParams();
        params.width = Math.max(1, (int) Math.round(frameWidth * scale));
        params.height = Math.max(1, (int) Math.round(frameHeight * scale));
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        picture.set(video.getLeft(), video.getTop(), video.getRight(), video.getBottom());
    }

    void setHostCursor(int width, int height, int hotspotX, int hotspotY, byte[] rgba) {
        Bitmap bitmap = null;
        hostIcon = null;
        if (width > 0 && height > 0 && width <= 128 && height <= 128
                && hotspotX >= 0 && hotspotX < width && hotspotY >= 0 && hotspotY < height
                && rgba != null && rgba.length == width * height * 4) {
            int[] argb = new int[width * height];
            for (int i = 0; i < argb.length; i++) {
                int offset = 4 * i;
                argb[i] = Color.argb(rgba[offset + 3] & 0xff, rgba[offset] & 0xff,
                        rgba[offset + 1] & 0xff, rgba[offset + 2] & 0xff);
            }
            bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            hostIcon = PointerIcon.create(bitmap, hotspotX, hotspotY);
        }
        PointerIcon icon = hostIcon == null ? PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_ARROW) : hostIcon;
        setPointerIcon(icon);
        video.setPointerIcon(icon);
        cursor.shape = bitmap;
        cursor.hotspotX = hotspotX;
        cursor.hotspotY = hotspotY;
        cursor.invalidate();
    }

    void capturePointer() {
        if (!inputEnabled) return;
        requestFocus();
        if (!hasPointerCapture()) requestPointerCapture();
    }

    void clearInput() {
        if (session != null) session.releaseInput();
        heldKeys.clear();
        buttons = 0;
        touchpadSecondary = false;
        touchDrag = false;
        if (hasPointerCapture()) releasePointerCapture();
    }

    @Override public void onPointerCaptureChange(boolean captured) {
        super.onPointerCaptureChange(captured);
        if (!captured && buttons != 0) {
            buttons = 0;
            touchpadSecondary = false;
            sendPointer(true);
        }
        cursor.invalidate();
    }

    boolean sendKey(KeyEvent event) {
        if (!inputEnabled || session == null) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && isCloudBookTouchpad(event.getDevice())) {
            // The stock firmware reports the pad's secondary click as Back.
            if (event.getAction() == KeyEvent.ACTION_DOWN) setTouchpadSecondary(true);
            if (event.getAction() == KeyEvent.ACTION_UP) setTouchpadSecondary(false);
            return true;
        }
        if (event.isSystem() && !RemoteKeymap.isEscape(event)) return false;
        long identity = ((long) event.getDeviceId() << 32) | (event.getKeyCode() & 0xffffffffL);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Integer held = heldKeys.get(identity);
            if (held != null) {
                if (!KeyEvent.isModifierKey(event.getKeyCode())) session.key(held, true);
                return true;
            }
            int value = RemoteKeymap.symbol(event);
            if (value == 0) return false;
            if (event.getRepeatCount() != 0) return true;
            boolean alreadyHeld = heldKeys.containsValue(value);
            heldKeys.put(identity, value);
            if (!alreadyHeld) session.key(value, true);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Integer value = heldKeys.remove(identity);
            if (value == null) return RemoteKeymap.symbol(event) != 0;
            if (!heldKeys.containsValue(value)) session.key(value, false);
            return true;
        }
        return false;
    }

    void tapKey(int symbol) {
        if (!inputEnabled || session == null || symbol == 0) return;
        session.key(symbol, true);
        session.key(symbol, false);
    }

    private void type(CharSequence value) {
        value.toString().codePoints().forEach(character -> tapKey(RemoteKeymap.codePoint(character)));
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                type(text);
                return true;
            }

            @Override public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < Math.min(before, 32); i++) tapKey(0xff08);
                for (int i = 0; i < Math.min(after, 32); i++) tapKey(0xffff);
                return true;
            }
        };
    }

    @Override public boolean onCapturedPointerEvent(MotionEvent event) { return mouse(event); }

    @Override public boolean onHoverEvent(MotionEvent event) {
        return mouse(event) || super.onHoverEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return mouse(event) || super.onGenericMotionEvent(event);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return inputEnabled; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!inputEnabled) return false;
        if (isMouse(event)) return mouse(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!placeAbsolute(event.getX(), event.getY(), false)) return false;
                touchDrag = true;
                buttons = 1;
                sendPointer(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touchDrag) {
                    placeAbsolute(event.getX(), event.getY(), true);
                    sendPointer(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchDrag) {
                    placeAbsolute(event.getX(), event.getY(), true);
                    touchDrag = false;
                    buttons = 0;
                    sendPointer(true);
                }
                return true;
            default:
                return true;
        }
    }

    private static boolean isMouse(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_MOUSE)
                || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }

    private static boolean isCloudBookTouchpad(InputDevice device) {
        return device != null && device.getVendorId() == 0x0911 && device.getProductId() == 0x5288;
    }

    private void setTouchpadSecondary(boolean pressed) {
        if (pressed == touchpadSecondary) return;
        touchpadSecondary = pressed;
        if (!positioned && !picture.isEmpty()) {
            placeAbsolute(picture.exactCenterX(), picture.exactCenterY(), true);
        }
        buttons = pressed ? buttons | 2 : buttons & ~2;
        sendPointer(true);
    }

    private boolean mouse(MotionEvent event) {
        if (!inputEnabled || !isMouse(event) || picture.isEmpty()) return false;
        int action = event.getActionMasked();
        int nextButtons = fromAndroidButtons(event.getButtonState());
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) nextButtons = 0;
        if (touchpadSecondary) nextButtons |= 2;
        if (action == MotionEvent.ACTION_DOWN && nextButtons == 0 && !isCloudBookTouchpad(event.getDevice())) {
            nextButtons = 1;
        }
        boolean relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);
        switch (action) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_SCROLL:
                if (relative) {
                    if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
                        placeRelative(event.getX(), event.getY());
                    }
                } else if (!placeAbsolute(event.getX(), event.getY(), buttons != 0 || hasPointerCapture())) {
                    return true;
                }
                break;
            default:
                return false;
        }
        boolean changed = buttons != nextButtons;
        buttons = nextButtons;
        if (action == MotionEvent.ACTION_SCROLL) {
            if (changed) sendPointer(true);
            float horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (Float.isFinite(horizontal) && Float.isFinite(vertical)
                    && (horizontal != 0 || vertical != 0)) {
                if (isCloudBookTouchpad(event.getDevice())) {
                    vertical *= CLOUDBOOK_TOUCHPAD_VERTICAL_SCROLL_SCALE;
                }
                session.scroll(remoteX(), remoteY(), buttons, horizontal, vertical);
            }
        } else {
            sendPointer(changed);
        }
        if (hasPointerCapture()) cursor.invalidate();
        return true;
    }

    private static int fromAndroidButtons(int flags) {
        return ((flags & MotionEvent.BUTTON_PRIMARY) != 0 ? 1 : 0)
                | ((flags & MotionEvent.BUTTON_SECONDARY) != 0 ? 2 : 0)
                | ((flags & MotionEvent.BUTTON_TERTIARY) != 0 ? 4 : 0);
    }

    private boolean placeAbsolute(float x, float y, boolean clamp) {
        if (picture.isEmpty() || (!clamp && !picture.contains((int) x, (int) y))) return false;
        pointerX = Math.max(picture.left, Math.min(picture.right - 1, x));
        pointerY = Math.max(picture.top, Math.min(picture.bottom - 1, y));
        positioned = true;
        return true;
    }

    private void placeRelative(float x, float y) {
        if (!positioned) {
            pointerX = picture.exactCenterX();
            pointerY = picture.exactCenterY();
        }
        placeAbsolute(pointerX + x, pointerY + y, true);
    }

    private int remoteX() {
        int position = Math.round((pointerX - picture.left) * remoteWidth / picture.width());
        return Math.max(0, Math.min(remoteWidth - 1, position));
    }

    private int remoteY() {
        int position = Math.round((pointerY - picture.top) * remoteHeight / picture.height());
        return Math.max(0, Math.min(remoteHeight - 1, position));
    }

    private void sendPointer(boolean changed) {
        if (positioned && !picture.isEmpty()) session.pointer(changed, remoteX(), remoteY(), buttons);
    }

    private final class CursorOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        Bitmap shape;
        int hotspotX;
        int hotspotY;

        CursorOverlay(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            arrow.moveTo(1, 1);
            arrow.lineTo(1, 18);
            arrow.lineTo(5, 14);
            arrow.lineTo(9, 21);
            arrow.lineTo(12, 19);
            arrow.lineTo(8, 12);
            arrow.lineTo(14, 12);
            arrow.close();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!hasPointerCapture() || !positioned) return;
            if (shape != null) {
                canvas.drawBitmap(shape, pointerX - hotspotX, pointerY - hotspotY, null);
                return;
            }
            canvas.save();
            canvas.translate(pointerX, pointerY);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(Color.WHITE);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawPath(arrow, paint);
            canvas.restore();
        }
    }
}
