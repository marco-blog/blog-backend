package net.java21.blog.backend.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 외부 HTTP 흉내(005 T008). JDK {@link HttpServer}로 루프백 임의 포트에 뜨고 경로별 응답(상태·본문·Content-Type·지연)을 정하며 받은 요청을
 * 기록한다. 테스트에서 try-with-resources로 쓴다.
 * <pre>{@code
 * try (StubHttpServer server = StubHttpServer.start()) {
 *     server.respond("/siteverify", 200, "application/json", "{\"success\":true}");
 *     ... server.uri("/siteverify") ...
 *     assertThat(server.requests()).singleElement().satisfies(r -> assertThat(r.body()).contains("secret="));
 * }
 * }</pre>
 */
public final class StubHttpServer implements AutoCloseable {

    /** 받은 요청. */
    public record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private record Stub(int status, String contentType, String body, Duration delay) {
    }

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private StubHttpServer(HttpServer server) {
        this.server = server;
    }

    public static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubHttpServer stub = new StubHttpServer(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public StubHttpServer respond(String path, int status, String contentType, String body) {
        return respond(path, status, contentType, body, Duration.ZERO);
    }

    public StubHttpServer respond(String path, int status, String contentType, String body, Duration delay) {
        stubs.put(path, new Stub(status, contentType, body, delay));
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port() + path);
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            URI uri = exchange.getRequestURI();
            requests.add(new Recorded(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(),
                    Map.copyOf(exchange.getRequestHeaders()), body));
            Stub stub = stubs.getOrDefault(uri.getPath(), new Stub(404, "text/plain", "not found", Duration.ZERO));
            if (!stub.delay().isZero()) {
                try {
                    Thread.sleep(stub.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] bytes = stub.body() == null ? new byte[0] : stub.body().getBytes(StandardCharsets.UTF_8);
            if (stub.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", stub.contentType());
            }
            exchange.sendResponseHeaders(stub.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService executor) {
            executor.shutdownNow();
        }
    }
}
