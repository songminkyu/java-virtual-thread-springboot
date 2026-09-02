package com.example.vtdemo.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HikariCP 를 흉내 낸 가짜 리포지토리.
 *
 * <p>진짜 DB 없이도 "커넥션은 유한하다"는 제약을 재현하기 위해, 내부적으로
 * 커넥션 개수만큼의 permit을 가진 세마포어를 두고 3초 안에 얻지 못하면
 * HikariCP 와 동일한 형태의 예외를 던진다.
 *
 * <pre>
 * HikariPool-1 - Connection is not available, request timed out after 3000ms
 * </pre>
 *
 * <p>실무에서는 이 클래스가 {@code JpaRepository} 이고, 아래 제약은 커넥션 풀이 대신 만든다.
 */
@Repository
public class OrderRepository {

    private final Semaphore connections;
    private final int poolSize;
    private final long queryLatencyMs;

    /** 지금까지 관찰된 최대 동시 커넥션 사용 수 (테스트/모니터링용). */
    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicInteger maxInUse = new AtomicInteger();

    public OrderRepository(@Value("${app.db.pool-size:10}") int poolSize,
                           @Value("${app.db.query-latency-ms:30}") long queryLatencyMs) {
        this.poolSize = poolSize;
        this.queryLatencyMs = queryLatencyMs;
        this.connections = new Semaphore(poolSize);
    }

    public Optional<Order> findById(long id) throws InterruptedException {
        borrowConnection();
        try {
            Thread.sleep(queryLatencyMs); // 쿼리 수행 흉내 (블로킹 I/O → 가상 스레드는 여기서 unmount)
            return Optional.of(new Order(id, "customer-" + id, (int) (1000 + id * 10), "CONFIRMED"));
        } finally {
            returnConnection();
        }
    }

    private void borrowConnection() throws InterruptedException {
        if (!connections.tryAcquire(3, TimeUnit.SECONDS)) {
            throw new ConnectionTimeoutException(
                    "HikariPool-1 - Connection is not available, request timed out after 3000ms");
        }
        int now = inUse.incrementAndGet();
        maxInUse.updateAndGet(prev -> Math.max(prev, now));
    }

    private void returnConnection() {
        inUse.decrementAndGet();
        connections.release();
    }

    public int poolSize() {
        return poolSize;
    }

    public int maxObservedConnectionsInUse() {
        return maxInUse.get();
    }

    public void resetStats() {
        maxInUse.set(0);
    }

    /** HikariCP 의 커넥션 획득 타임아웃에 대응하는 예외. */
    public static class ConnectionTimeoutException extends RuntimeException {
        public ConnectionTimeoutException(String message) {
            super(message);
        }
    }
}
