package com.example.vtdemo.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세마포어가 커넥션 풀을 실제로 보호하는지 검증한다.
 * (Spring 컨텍스트 없이 순수 JUnit 으로 돌아가므로 빠르다)
 */
class OrderServiceSemaphoreTest {

    private static final int POOL_SIZE = 5;
    private static final int QUERY_MS = 30;
    private static final int CONCURRENT_REQUESTS = 100;

    @Test
    @DisplayName("세마포어를 적용하면 커넥션이 5개뿐이어도 요청 100개가 모두 성공한다")
    void semaphoreProtectsConnectionPool() {
        OrderRepository repository = new OrderRepository(POOL_SIZE, QUERY_MS);
        OrderService service = new OrderService(repository, POOL_SIZE);

        var failures = new ConcurrentLinkedQueue<String>();
        var successes = new java.util.concurrent.atomic.AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, CONCURRENT_REQUESTS).forEach(i -> executor.submit(() -> {
                try {
                    service.findOrder(i);
                    successes.incrementAndGet();
                } catch (OrderRepository.ConnectionTimeoutException e) {
                    failures.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        assertEquals(CONCURRENT_REQUESTS, successes.get(), "모든 요청이 성공해야 한다");
        assertTrue(failures.isEmpty(), "커넥션 획득 실패가 없어야 한다. 실패: " + failures.size());
        assertTrue(repository.maxObservedConnectionsInUse() <= POOL_SIZE,
                "동시에 쓰인 커넥션 수가 풀 크기를 넘으면 안 된다. 관찰된 최대치: "
                        + repository.maxObservedConnectionsInUse());
    }

    @Test
    @DisplayName("세마포어 없이 호출하면 커넥션 획득 타임아웃이 발생한다")
    void withoutSemaphoreConnectionsAreExhausted() {
        // 커넥션 2개짜리 풀에 아주 짧은 대기 시간을 가진 쿼리를 대량으로 던진다.
        OrderRepository repository = new OrderRepository(2, 200);
        OrderService service = new OrderService(repository, 2);

        var failures = new java.util.concurrent.atomic.AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 200).forEach(i -> executor.submit(() -> {
                try {
                    service.findOrderWithoutLimiter(i); // 세마포어를 거치지 않는 경로
                } catch (OrderRepository.ConnectionTimeoutException e) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        assertTrue(failures.get() > 0,
                "커넥션 2개에 요청 200개를 던지면 타임아웃이 나야 한다. 실패 건수: " + failures.get());
    }
}
