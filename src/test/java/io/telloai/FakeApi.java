package io.telloai;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Local HTTP server standing in for the public REST API in {@link TelloApi} tests.
 * Records every request and answers each with the next queued reply; with none
 * queued it answers {@code 599} with an empty body so a missing reply is obvious.
 */
final class FakeApi implements AutoCloseable {

    /** One request as the server saw it. */
    record Request(String method, String path, String query, Headers headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private record Reply(int status, String body, String contentType, long delayMillis) {
    }

    private final HttpServer server;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedDeque<Reply> replies = new ConcurrentLinkedDeque<>();
    private volatile Reply fallback = new Reply(599, "", "text/plain", 0);

    FakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-api");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL as an SDK user would pass it, e.g. {@code http://127.0.0.1:1234/v1}. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    FakeApi reply(int status, String json) {
        replies.add(new Reply(status, json, "application/json", 0));
        return this;
    }

    FakeApi replyRaw(int status, String body, String contentType) {
        replies.add(new Reply(status, body, contentType, 0));
        return this;
    }

    FakeApi replyAfter(long delayMillis, int status, String json) {
        replies.add(new Reply(status, json, "application/json", delayMillis));
        return this;
    }

    /** The reply once the queued ones run out. */
    FakeApi replyOtherwise(int status, String json) {
        fallback = new Reply(status, json, "application/json", 0);
        return this;
    }

    /** The next request received, waiting up to 5 seconds. */
    Request take() throws InterruptedException {
        Request request = requests.poll(5, TimeUnit.SECONDS);
        if (request == null) {
            throw new AssertionError("no request reached the fake API");
        }
        return request;
    }

    int requestCount() {
        return requests.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] in = exchange.getRequestBody().readAllBytes();
        requests.add(new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders(),
                new String(in, StandardCharsets.UTF_8)));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = fallback;
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reply.status() == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
