package io.github.kuaner.cloudbook.switcher;

import android.content.Context;
import android.util.Base64;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbCrypto;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

final class AndroidBootSwitch {
    static final String DEVICE = "rk3568_r_w600d2";
    private static final String EXIT_MARKER = "\n__CLOUDBOOK_EXIT__=";
    private static final String EMPTY_COMMAND =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private static final String REQUIRE_STOCK_ANDROID = """
            [ "$(id -u)" = 0 ] || exit 20
            [ "$(getprop ro.product.device)" = rk3568_r_w600d2 ] &&
                [ "$(getprop ro.boot.slot_suffix)" = _b ] || exit 21
            """;

    // Power operations use the same authorized loopback connection as the slot switch.
    private enum PowerRequest {
        REBOOT("reboot"), SHUTDOWN("shutdown,userrequested");

        final String value;

        PowerRequest(String value) {
            this.value = value;
        }
    }

    private static final String CHECK = REQUIRE_STOCK_ANDROID + String.format("""
            by_name=/dev/block/by-name
            misc=$by_name/misc
            boot=$by_name/boot_a
            [ -b "$misc" ] && [ -b "$boot" ] && [ -b "$by_name/rootfs" ] || exit 22
            hex_at() { od -An -v -tx1 -j "$1" -N "$2" "$3" | tr -d ' \\r\\n'; }
            [ "$(hex_at 0 8 "$boot")" = 414e44524f494421 ] || exit 22
            dd if="$boot" bs=64 skip=1 count=8 2>/dev/null | tr '\\000' '\\n' |
                grep -Eq '(^|[[:space:]])root=LABEL=EMMC_ROOTFS([[:space:]]|$)' || exit 22
            [ "$(hex_at 0 32 "$misc")" = '%s' ] &&
                [ "$(hex_at 16384 32 "$misc")" = '%s' ] || exit 23
            record=$(hex_at 2048 32 "$misc")
            """, EMPTY_COMMAND, EMPTY_COMMAND);

    private final Context context;

    AndroidBootSwitch(Context context) {
        this.context = context.getApplicationContext();
    }

    void check() throws Exception {
        try (LocalAdb connection = connect()) {
            BootRecord.selectLinux(readRecord(connection));
        }
    }

    void reboot() throws Exception {
        requestPower(PowerRequest.REBOOT);
    }

    void shutdown() throws Exception {
        requestPower(PowerRequest.SHUTDOWN);
    }

    private void requestPower(PowerRequest request) throws Exception {
        try (LocalAdb connection = connect()) {
            requestPower(connection, request);
        }
    }

    private static void requestPower(LocalAdb connection, PowerRequest request) throws IOException {
        rootShell(connection, REQUIRE_STOCK_ANDROID + "setprop sys.powerctl " + request.value);
    }

    void rebootToLinux() throws Exception {
        try (LocalAdb connection = connect()) {
            String original = readRecord(connection);
            String updated = BootRecord.hex(BootRecord.selectLinux(original));
            rootShell(connection, CHECK + String.format("""
                    [ "$record" = '%s' ] || exit 24
                    write_record() {
                        printf '%%b' "$1" | dd of="$misc" bs=32 seek=64 conv=notrunc 2>/dev/null &&
                            sync && [ "$(hex_at 2048 32 "$misc")" = "$2" ]
                    }
                    if ! write_record '%s' '%s'; then
                        write_record '%s' '%s' && exit 25
                        exit 26
                    fi
                    """, original, BootRecord.escapedBytes(updated), updated,
                    BootRecord.escapedBytes(original), original));
            try {
                requestPower(connection, PowerRequest.REBOOT);
            } catch (IOException error) {
                throw new IOException("已设置下次启动进入 Linux。若系统未自动重启，请从 Android 电源菜单重启。", error);
            }
        }
    }

    private static String readRecord(LocalAdb connection) throws IOException {
        return rootShell(connection, CHECK + "printf '%s\\n' \"$record\"");
    }

    private LocalAdb connect() throws Exception {
        File privateKey = new File(context.getNoBackupFilesDir(), "local-adb.key");
        File publicKey = new File(context.getNoBackupFilesDir(), "local-adb.pub");
        AdbBase64 base64 = bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP);
        AdbCrypto crypto;
        if (privateKey.isFile() && publicKey.isFile()) {
            crypto = AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey);
        } else {
            crypto = AdbCrypto.generateAdbKeyPair(base64);
            crypto.saveAdbKeyPair(privateKey, publicKey);
        }

        try {
            return new LocalAdb(crypto);
        } catch (IOException | GeneralSecurityException error) {
            throw new IOException("本机 ADB 未连接。请确认 Android 已启用网络 ADB，并在系统提示中允许调试。", error);
        }
    }

    private static String rootShell(LocalAdb connection, String command) throws IOException {
        String reply = connection.request("shell:su 0 /system/bin/sh -c " + quote(command) +
                "; printf '\\n__CLOUDBOOK_EXIT__=%d\\n' $?");
        int marker = reply.lastIndexOf(EXIT_MARKER);
        if (marker == -1) {
            throw new IOException("Android 未返回操作结果，请确认设备状态后再试。");
        }
        String status = reply.substring(marker + EXIT_MARKER.length()).trim();
        return switch (status) {
            case "0" -> reply.substring(0, marker).trim();
            case "21" -> throw new IOException("请从 CloudBook Air 的原厂 Android B 槽启动。");
            case "22" -> throw new IOException("找不到支持的 Linux A 槽或内置 Linux 根分区。");
            case "23" -> throw new IOException("启动控制区有待执行的命令，请先完成更新或恢复操作。");
            case "24" -> throw new IOException("槽位状态刚刚发生变化，请重新检查后再切换。");
            case "25" -> throw new IOException("写入校验失败，已恢复原槽位。");
            case "26" -> throw new IOException("写入校验和恢复均失败，请先检查槽位记录。");
            case "20", "126", "127" -> throw new IOException("原厂 Android 的 ADB shell 未能取得所需的 root 权限。");
            default -> throw new IOException("Android 拒绝执行操作，请检查原厂系统与 ADB 授权。");
        };
    }

    private static String quote(String command) {
        return "'" + command.replace("'", "'\\''") + "'";
    }
}
