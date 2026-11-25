// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kuaner.cloudbook.remote;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

final class RemoteKeymap {
    private RemoteKeymap() {}

    static boolean isEscape(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) return true;
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK || event.getScanCode() != 1) return false;
        InputDevice device = event.getDevice();
        // CloudBook's stock key layout maps the physical Escape key to Back.
        return device != null && device.getVendorId() == 0x258a && device.getProductId() == 1;
    }

    static int symbol(KeyEvent event) {
        if (isEscape(event)) return 0xff1b;
        int code = event.getKeyCode();
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) {
            return 0xffbe + code - KeyEvent.KEYCODE_F1;
        }
        int special = switch (code) {
            case KeyEvent.KEYCODE_DEL -> 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff;
            case KeyEvent.KEYCODE_TAB -> 0xff09;
            case KeyEvent.KEYCODE_ENTER -> 0xff0d;
            case KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff8d;
            case KeyEvent.KEYCODE_INSERT -> 0xff63;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP -> 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME -> 0xff50;
            case KeyEvent.KEYCODE_MOVE_END -> 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP -> 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56;
            case KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3;
            case KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4;
            case KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9;
            case KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea;
            case KeyEvent.KEYCODE_META_LEFT -> 0xffeb;
            case KeyEvent.KEYCODE_META_RIGHT -> 0xffec;
            case KeyEvent.KEYCODE_CAPS_LOCK -> 0xffe5;
            default -> 0;
        };
        if (special != 0) return special;
        int disallowed = KeyEvent.META_CTRL_MASK | KeyEvent.META_META_MASK | KeyEvent.META_ALT_MASK;
        int character = event.getUnicodeChar(event.getMetaState() & ~disallowed);
        if ((character & KeyCharacterMap.COMBINING_ACCENT) != 0) character &= KeyCharacterMap.COMBINING_ACCENT_MASK;
        return codePoint(character);
    }

    static int codePoint(int character) {
        if (character == '\n' || character == '\r') return 0xff0d;
        if (character == '\t') return 0xff09;
        if (character < 32 || !Character.isValidCodePoint(character)) return 0;
        return character <= 0xff ? character : 0x01000000 | character;
    }
}
