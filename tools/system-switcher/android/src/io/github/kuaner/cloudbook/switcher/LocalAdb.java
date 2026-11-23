package io.github.kuaner.cloudbook.switcher;

import static com.tananaev.adblib.AdbProtocol.*;

import com.tananaev.adblib.AdbCrypto;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** One synchronous ADB stream at a time over the device's loopback interface. */
final class LocalAdb implements Closeable {
    private final Socket socket = new Socket();
    private final DataInputStream input;
    private int nextId;
    private int maxPayload;

    LocalAdb(AdbCrypto crypto) throws IOException, GeneralSecurityException {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            socket.setSoTimeout(60000);
            input = new DataInputStream(socket.getInputStream());
            send(generateConnect());
            Packet reply = receive();
            if (!isChallenge(reply)) {
                throw new IOException("Expected an authenticated ADB connection");
            }
            send(generateAuth(AUTH_TYPE_SIGNATURE, crypto.signAdbTokenPayload(reply.payload)));
            reply = receive();
            if (isChallenge(reply)) {
                String key = new String(crypto.getAdbPublicKeyPayload(), StandardCharsets.UTF_8)
                        .replace(" unknown@unknown", " switcher@cloudbook");
                send(generateAuth(AUTH_TYPE_RSA_PUBLIC, key.getBytes(StandardCharsets.UTF_8)));
                reply = receive();
            }
            if (reply.command != CMD_CNXN || reply.second <= 0) {
                throw new IOException("ADB authorization was declined");
            }
            maxPayload = Math.min(reply.second, CONNECT_MAXDATA);
            socket.setSoTimeout(10000);
        } catch (IOException | GeneralSecurityException error) {
            socket.close();
            throw error;
        }
    }

    private static boolean isChallenge(Packet packet) {
        return packet.command == CMD_AUTH && packet.first == AUTH_TYPE_TOKEN &&
                packet.payload.length == 20;
    }

    String request(String service) throws IOException {
        int local = ++nextId;
        byte[] open = generateOpen(local, service);
        if (open.length - ADB_HEADER_LENGTH > maxPayload) {
            throw new IOException("ADB request exceeds the device's packet limit");
        }
        send(open);
        Packet reply = receive();
        if (reply.command != CMD_OKAY || reply.second != local) {
            throw new IOException("ADB could not open the requested service");
        }
        int remote = reply.first;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            reply = receive();
            if (reply.second != local || reply.first != remote) {
                throw new IOException("Unexpected ADB stream identifier");
            }
            if (reply.command == CMD_CLSE) {
                send(generateClose(local, remote));
                return new String(output.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            if (reply.command != CMD_WRTE || output.size() + reply.payload.length > CONNECT_MAXDATA) {
                throw new IOException("Unexpected ADB stream data");
            }
            output.write(reply.payload, 0, reply.payload.length);
            send(generateReady(local, remote));
        }
    }

    private void send(byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
    }

    private Packet receive() throws IOException {
        byte[] header = new byte[ADB_HEADER_LENGTH];
        input.readFully(header);
        ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = bytes.getInt();
        int first = bytes.getInt();
        int second = bytes.getInt();
        int length = bytes.getInt();
        int checksum = bytes.getInt();
        int magic = bytes.getInt();
        if (magic != ~command || length < 0 || length > CONNECT_MAXDATA) {
            throw new IOException("Invalid ADB packet header");
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        int sum = 0;
        for (byte value : payload) {
            sum += Byte.toUnsignedInt(value);
        }
        if (checksum != sum) {
            throw new IOException("Invalid ADB packet checksum");
        }
        return new Packet(command, first, second, payload);
    }

    private static final class Packet {
        final int command;
        final int first;
        final int second;
        final byte[] payload;

        Packet(int command, int first, int second, byte[] payload) {
            this.command = command;
            this.first = first;
            this.second = second;
            this.payload = payload;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
