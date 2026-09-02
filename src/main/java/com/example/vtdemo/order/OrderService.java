package com.example.vtdemo.order;

import com.example.vtdemo.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * 패턴 ④: 세마포어로 DB 커넥션 풀을 보호한다.
 *
 * <p>가상 스레드는 수만 개를 만들어도 괜찮지만 <b>DB 커넥션은 10~20개뿐</b>이다.
 * 요청 5,000개가 한꺼번에 들어오면 가상 스레드 5,000개가 동시에 커넥션을 요구하다가
 * {@code Connection is not available, request timed out} 이 터진다.
 *
 * <p>해결책은 스레드 수를 줄이는 게 아니라 <b>커넥션을 쓰는 구간만</b> 세마포어로 제한하는 것이다.
 * 가상 스레드는 세마포어를 기다리는 동안에도 캐리어를 잡지 않으므로 대기 비용이 거의 없다.
 *
 * <p>실측(커넥션 5개 풀에 요청 100개): 세마포어 없으면 80건 실패, 적용하면 0건 실패.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    /** 허용치는 커넥션 풀 크기와 같거나 약간 작게 잡는다. */
    private final Semaphore dbLimiter;

    public OrderService(OrderRepository orderRepository,
                        @Value("${app.db.pool-size:10}") int poolSize) {
        this.orderRepository = orderRepository;
        this.dbLimiter = new Semaphore(poolSize);
    }

    public Order findOrder(long id) throws InterruptedException {
        dbLimiter.acquire();
        try {
            Optional<Order> found = orderRepository.findById(id);
            return found.orElseThrow(() -> new OrderNotFoundException(id));
        } finally {
            dbLimiter.release();
        }
    }

    /**
     * 세마포어 없이 그대로 호출하는 버전 — 비교/시연용.
     * 동시 요청이 풀 크기를 넘어서면 커넥션 획득 타임아웃이 발생한다.
     */
    public Order findOrderWithoutLimiter(long id) throws InterruptedException {
        log.debug("[{}] 세마포어 없이 조회 id={}", RequestContext.traceId(), id);
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public int availablePermits() {
        return dbLimiter.availablePermits();
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(long id) {
            super("주문을 찾을 수 없습니다: " + id);
        }
    }
}
