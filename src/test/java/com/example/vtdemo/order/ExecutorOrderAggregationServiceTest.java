package com.example.vtdemo.order;

import com.example.vtdemo.external.ExternalClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기본 구현(표준 API, preview 불필요)이 구조적 동시성과 동일하게 동작하는지 검증한다.
 * 아무 플래그 없이 실행된다.
 */
class ExecutorOrderAggregationServiceTest {

    // 주문 300ms / 결제 500ms / 배송 400ms → 순차 호출이면 1200ms
    private final ExternalClients clients = new ExternalClients(300, 500, 400);
    private final OrderAggregator aggregator = new ExecutorOrderAggregationService(clients);

    @Test
    @DisplayName("3개 서비스를 동시에 호출하면 가장 느린 하나(500ms) 수준으로 끝난다")
    void fanOutTakesAsLongAsTheSlowestCall() throws Exception {
        long start = System.currentTimeMillis();
        OrderDetails details = aggregator.getOrderDetails(42);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(details.order());
        assertNotNull(details.payment());
        assertNotNull(details.shipping());
        assertEquals(42, details.order().orderId());
        assertEquals("PAID", details.payment().status(), "결과가 순서대로 매핑되어야 한다");

        assertTrue(elapsed < 1000, "순차 호출(1200ms)보다 훨씬 빨라야 한다. 실제: " + elapsed + "ms");
        assertTrue(elapsed >= 500, "가장 느린 호출(500ms)보다는 오래 걸린다. 실제: " + elapsed + "ms");
    }

    @Test
    @DisplayName("하나가 실패하면 3초짜리 형제 작업이 즉시 취소된다")
    void failureCancelsSiblingTasks() {
        long start = System.currentTimeMillis();

        assertThrows(Exception.class, () -> aggregator.getOrderDetailsWithFailure(42));

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1000,
                "3초짜리 배송 조회를 기다리지 않고 취소되어야 한다. 실제: " + elapsed + "ms");
    }

    @Test
    @DisplayName("가장 먼저 성공한 리전의 응답을 채택한다")
    void fastestReplicaWins() throws Exception {
        long start = System.currentTimeMillis();
        var order = aggregator.getOrderFromFastestReplica(7);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("CONFIRMED(replica)", order.status(), "더 빠른 쪽(100ms)의 응답이어야 한다");
        assertTrue(elapsed < 300, "느린 쪽(300ms)을 기다리면 안 된다. 실제: " + elapsed + "ms");
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

    @Test
    @DisplayName("타임아웃을 넉넉히 주면 정상적으로 완료된다")
    void completesWithinGenerousTimeout() throws Exception {
        OrderDetails details = aggregator.getOrderDetailsWithTimeout(42, Duration.ofSeconds(5));
        assertNotNull(details.payment());
        assertEquals("PAID", details.payment().status());
    }
}
