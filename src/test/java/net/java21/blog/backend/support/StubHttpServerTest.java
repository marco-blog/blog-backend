package net.java21.blog.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/** 005 T008: 시험용 HTTP 서버 자체 시험. */
class StubHttpServerTest {

    @Test
    void respondsPerPathAndRecordsRequests() throws Exception {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.respond("/ok", 201, "application/json", "{\"a\":1}");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(server.uri("/ok?x=1"))
                    .header("X-Test", "yes").POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> missing = client.send(HttpRequest.newBuilder(server.uri("/none")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(ok.statusCode()).isEqualTo(201);
            assertThat(ok.body()).isEqualTo("{\"a\":1}");
            assertThat(ok.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(server.requests()).hasSize(2);
            StubHttpServer.Recorded first = server.requests().get(0);
            assertThat(first.method()).isEqualTo("POST");
            assertThat(first.path()).isEqualTo("/ok");
            assertThat(first.query()).isEqualTo("x=1");
            assertThat(first.body()).isEqualTo("hello");
            assertThat(first.header("x-test")).isEqualTo("yes");
            assertThat(first.header("missing")).isNull();
            assertThat(server.port()).isPositive();
        }
    }
}
