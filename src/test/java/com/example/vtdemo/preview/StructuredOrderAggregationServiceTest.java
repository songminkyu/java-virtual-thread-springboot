package com.example.vtdemo.preview;

import com.example.vtdemo.external.ExternalClients;
import com.example.vtdemo.order.OrderAggregator;
import com.example.vtdemo.order.OrderDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StructuredTaskScope 구현 검증 — <b>preview 를 켰을 때만</b> 컴파일/실행된다.
 *
 * <pre>./gradlew test -PenablePreview</pre>
 *
 * 기대 결과는 {@code ExecutorOrderAggregationServiceTest} 와 동일하다.
 * 두 구현이 같은 계약을 만족한다는 것을 같은 단언으로 확인하는 셈이다.
 */
class StructuredOrderAggregationServiceTest {

    private final ExternalClients clients = new ExternalClients(300, 500, 400);
    private final OrderAggregator aggregator = new StructuredOrderAggregationService(clients);

    @Test
    @DisplayName("3개 서비스를 동시에 호출하면 가장 느린 하나(500ms) 수준으로 끝난다")
    void fanOutTakesAsLongAsTheSlowestCall() throws Exception {
        long start = System.currentTimeMillis();
        OrderDetails details = aggregator.getOrderDetails(42);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(details.order());
        assertEquals("PAID", details.payment().status());
        assertTrue(elapsed < 1000, "순차 호출(1200ms)보다 훨씬 빨라야 한다. 실제: " + elapsed + "ms");
        assertTrue(elapsed >= 500, "가장 느린 호출(500ms)보다는 오래 걸린다. 실제: " + elapsed + "ms");
    }

    @Test
    @DisplayName("하나가 실패하면 3초짜리 형제 작업이 즉시 취소된다")
    void failureCancelsSiblingTasks() {
        long start = System.currentTimeMillis();

        assertThrows(Exception.class, () -> aggregator.getOrderDetailsWithFailure(42));

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1000, "3초짜리 작업을 기다리면 안 된다. 실제: " + elapsed + "ms");
    }

    @Test
    @DisplayName("가장 먼저 성공한 리전의 응답을 채택한다")
    void fastestReplicaWins() throws Exception {
        var order = aggregator.getOrderFromFastestReplica(7);
        assertEquals("CONFIRMED(replica)", order.status());
    }

    @Test
    @DisplayName("타임아웃이 걸리면 전체가 취소된다")
    void timeoutCancelsEverything() {
        long start = System.currentTimeMillis();

        assertThrows(Exception.class,
                () -> aggregator.getOrderDetailsWithTimeout(42, Duration.ofMillis(200)));

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 500, "200ms 타임아웃이 지켜져야 한다. 실제: " + elapsed + "ms");
    }
}
