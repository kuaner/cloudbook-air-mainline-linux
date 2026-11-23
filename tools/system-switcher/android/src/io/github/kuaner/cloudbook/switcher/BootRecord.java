package io.github.kuaner.cloudbook.switcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.zip.CRC32;

final class BootRecord {
    private static final int CRC_OFFSET = 28;

    static byte[] selectLinux(String hex) throws IOException {
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IOException("无法读取完整的启动槽位记录。");
        }
        byte[] record = new byte[32];
        for (int i = 0; i < record.length; i++) {
            record[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        ByteBuffer bytes = ByteBuffer.wrap(record);
        if (bytes.getInt(0) != 0x00414230 || bytes.getShort(4) != 0x0100 ||
                bytes.getInt(CRC_OFFSET) != checksum(record)) {
            throw new IOException("启动槽位记录的格式或校验有误。");
        }
        for (int base : new int[]{8, 12}) {
            if (Byte.toUnsignedInt(record[base]) > 15 ||
                    Byte.toUnsignedInt(record[base + 1]) > 7 ||
                    Byte.toUnsignedInt(record[base + 2]) > 1) {
                throw new IOException("启动槽位记录中的状态值无效。");
            }
        }
        if (record[12] == 0 || record[14] != 1) {
            throw new IOException("Android B 槽还未确认启动成功，无法保留回退槽位。");
        }

        // Match the repository's test_slot_a policy and preserve all reserved bytes.
        record[8] = 15;
        record[9] = 3;
        record[10] = 0;
        record[12] = 14;
        bytes.putInt(CRC_OFFSET, checksum(record));
        return record;
    }

    private static int checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, CRC_OFFSET);
        return (int) crc.getValue();
    }

    static String hex(byte[] record) {
        StringBuilder hex = new StringBuilder(record.length * 2);
        for (byte value : record) {
            hex.append(String.format(Locale.ROOT, "%02x", Byte.toUnsignedInt(value)));
        }
        return hex.toString();
    }

    static String escapedBytes(String hex) {
        StringBuilder escaped = new StringBuilder(hex.length() / 2 * 5);
        for (int i = 0; i < hex.length(); i += 2) {
            int value = Integer.parseInt(hex.substring(i, i + 2), 16);
            escaped.append(String.format(Locale.ROOT, "\\0%03o", value));
        }
        return escaped.toString();
    }
}
