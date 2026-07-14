package ai.tello;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Minimal RFC 6455 WebSocket server for exercising {@link TelloClient}'s connect /
 * auth handshake. It performs the upgrade, records the request line and
 * headers, queues every inbound text frame, and lets a test drive replies either
 * manually or via an {@link #onText(Consumer)} auto-responder.
 *
 * <p>Intentionally tiny: single connection, text frames only, no fragmentation,
 * payloads well under 64 KiB. It is a test double, not a production server.
 */
final class FakeGateway implements AutoCloseable {

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Thread thread;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final Map<String, String> requestHeaders = new LinkedHashMap<>();

    private volatile String requestTarget;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile Consumer<String> onText;

    private FakeGateway() throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.serverSocket.setReuseAddress(true);
        this.thread = new Thread(this::run, "fake-gateway");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    static FakeGateway start() throws IOException {
        return new FakeGateway();
    }

    String url() {
        return "ws://127.0.0.1:" + serverSocket.getLocalPort() + "/sdk";
    }

    /** Auto-responder invoked on the server thread for each inbound text frame. */
    void onText(Consumer<String> handler) {
        this.onText = handler;
    }

    /** The raw request target of the upgrade request, e.g. {@code /sdk}. */
    String requestTarget() {
        return requestTarget;
    }

    /** Upgrade request headers, keyed by lower-cased header name. */
    Map<String, String> requestHeaders() {
        return requestHeaders;
    }

    /** Block for the next inbound text frame. */
    String take() throws InterruptedException {
        return received.take();
    }

    /** Poll for an inbound text frame, returning null if none arrives in time. */
    String poll(long millis) throws InterruptedException {
        return received.poll(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    synchronized void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        OutputStream o = out;
        o.write(0x81); // FIN + text
        writeLength(o, payload.length);
        o.write(payload);
        o.flush();
    }

    synchronized void sendClose(int code, String reason) throws IOException {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + r.length];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(r, 0, payload, 2, r.length);
        OutputStream o = out;
        o.write(0x88); // FIN + close
        writeLength(o, payload.length);
        o.write(payload);
        o.flush();
    }

    private static void writeLength(OutputStream o, int len) throws IOException {
        if (len <= 125) {
            o.write(len);
        } else {
            o.write(126);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        }
    }

    private void run() {
        try {
            socket = serverSocket.accept();
            InputStream in = socket.getInputStream();
            out = socket.getOutputStream();
            handshake(in, out);
            readLoop(in);
        } catch (IOException ignored) {
            // socket closed / test finished
        }
    }

    private void handshake(InputStream in, OutputStream o) throws IOException {
        String request = readHttpRequest(in);
        String[] lines = request.split("\r\n");
        if (lines.length > 0) {
            String[] parts = lines[0].split(" ");
            if (parts.length >= 2) {
                requestTarget = parts[1];
            }
        }
        String key = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            requestHeaders.put(name.toLowerCase(), value);
            if ("sec-websocket-key".equalsIgnoreCase(name)) {
                key = value;
            }
        }
        String accept = base64Sha1(key + MAGIC);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        o.write(response.getBytes(StandardCharsets.US_ASCII));
        o.flush();
    }

    private static String readHttpRequest(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n'
                    && sb.charAt(n - 2) == '\r' && sb.charAt(n - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

    private void readLoop(InputStream in) throws IOException {
        while (true) {
            int b1 = in.read();
            if (b1 == -1) {
                return;
            }
            int opcode = b1 & 0x0F;
            int b2 = in.read();
            if (b2 == -1) {
                return;
            }
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                readFully(in, mask, 4);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload, (int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode) {
                case 0x1: // text
                    String text = new String(payload, StandardCharsets.UTF_8);
                    received.add(text);
                    Consumer<String> handler = onText;
                    if (handler != null) {
                        handler.accept(text);
                    }
                    break;
                case 0x8: // close
                    return;
                case 0x9: // ping -> pong
                    sendPong(payload);
                    break;
                default:
                    break;
            }
        }
    }

    private synchronized void sendPong(byte[] payload) throws IOException {
        OutputStream o = out;
        o.write(0x8A);
        writeLength(o, payload.length);
        o.write(payload);
        o.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new IOException("unexpected end of stream");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n == -1) {
                throw new IOException("unexpected end of stream");
            }
            off += n;
        }
    }

    private static String base64Sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
