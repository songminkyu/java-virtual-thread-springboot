package com.example.vtdemo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.threads.virtual.enabled: true} 가 실제로 동작하는지 검증한다.
 *
 * <p>테스트 클라이언트 대신 JDK 표준 {@link HttpClient}로 실제 포트에 요청을 보내
 * 서버가 요청을 가상 스레드에서 처리했는지 응답으로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VirtualThreadEnabledTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("HTTP 요청이 가상 스레드에서 처리된다")
    void requestIsHandledOnVirtualThread() throws Exception {
        HttpResponse<String> response = get("/api/thread-info");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"virtual\":true"),
                "요청이 가상 스레드에서 처리되어야 한다. 실제 응답: " + response.body());
        assertTrue(response.body().contains("VirtualThread"),
                "스레드 이름이 VirtualThread 여야 한다. 실제 응답: " + response.body());
    }

    @Test
    @DisplayName("ScopedValue로 바인딩한 트레이스 ID가 요청 전체에 전파된다")
    void traceIdIsPropagatedThroughScopedValue() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/thread-info"))
                .header("X-Trace-Id", "test-trace-1234")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("test-trace-1234"),
                "요청 헤더의 트레이스 ID가 응답 본문에 담겨야 한다. 실제 응답: " + response.body());
        assertEquals("test-trace-1234", response.headers().firstValue("X-Trace-Id").orElse(null));
    }

    @Test
    @DisplayName("동시 요청 200개가 모두 성공한다")
    void handlesManyConcurrentRequests() throws Exception {
        int concurrency = 200;
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    try {
                        results.add(get("/api/thread-info").statusCode());
                    } catch (Exception e) {
                        results.add(-1);
                    }
                });
            }
        }

        assertEquals(concurrency, results.size());
        assertTrue(results.stream().allMatch(code -> code == 200),
                "모든 요청이 200이어야 한다. 실제: " + results);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
