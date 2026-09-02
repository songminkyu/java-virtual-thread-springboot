package com.example.vtdemo.web;

import com.example.vtdemo.context.RequestContext;
import com.example.vtdemo.order.Order;
import com.example.vtdemo.order.OrderAggregationService;
import com.example.vtdemo.order.OrderAggregationService.OrderDetails;
import com.example.vtdemo.order.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 주문 조회 API.
 *
 * <ul>
 *   <li>{@code GET /api/orders/{id}}              — 세마포어로 커넥션 풀을 보호한 조회 (권장)</li>
 *   <li>{@code GET /api/orders/{id}/unprotected}  — 세마포어 없이 조회 (비교용, 부하 주면 실패)</li>
 *   <li>{@code GET /api/orders/{id}/details}      — 구조적 동시성으로 3개 서비스 동시 호출</li>
 *   <li>{@code GET /api/orders/{id}/details-fail} — 하나 실패 시 형제 작업 자동 취소 시연</li>
 *   <li>{@code GET /api/orders/{id}/fastest}      — 가장 빨리 응답한 리전 채택</li>
 *   <li>{@code GET /api/orders/{id}/details-timeout?timeoutMs=300} — 스코프 타임아웃</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderAggregationService aggregationService;

    public OrderController(OrderService orderService, OrderAggregationService aggregationService) {
        this.orderService = orderService;
        this.aggregationService = aggregationService;
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable long id) throws InterruptedException {
        return orderService.findOrder(id);
    }

    @GetMapping("/{id}/unprotected")
    public Order getOrderUnprotected(@PathVariable long id) throws InterruptedException {
        return orderService.findOrderWithoutLimiter(id);
    }

    @GetMapping("/{id}/details")
    public Map<String, Object> getOrderDetails(@PathVariable long id) throws Exception {
        long start = System.currentTimeMillis();
        OrderDetails details = aggregationService.getOrderDetails(id);
        return timed(details, start, "3개 서비스를 동시에 호출했다. 순차 호출이었다면 약 1200ms.");
    }

    @GetMapping("/{id}/details-fail")
    public Map<String, Object> getOrderDetailsWithFailure(@PathVariable long id) {
        long start = System.currentTimeMillis();
        try {
            OrderDetails details = aggregationService.getOrderDetailsWithFailure(id);
            return timed(details, start, "예상과 달리 성공했다.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return timed(Map.of("error", cause.getMessage()), start,
                    "결제 조회가 100ms 만에 실패하자 3초짜리 배송 조회가 즉시 취소되었다.");
        }
    }

    @GetMapping("/{id}/fastest")
    public Map<String, Object> getFromFastestReplica(@PathVariable long id) throws Exception {
        long start = System.currentTimeMillis();
        var result = aggregationService.getOrderFromFastestReplica(id);
        return timed(result, start, "가장 먼저 성공한 리전의 응답만 채택하고 나머지는 취소했다.");
    }

    @GetMapping("/{id}/details-timeout")
    public Map<String, Object> getOrderDetailsWithTimeout(
            @PathVariable long id,
            @RequestParam(defaultValue = "300") long timeoutMs) {

        long start = System.currentTimeMillis();
        try {
            OrderDetails details =
                    aggregationService.getOrderDetailsWithTimeout(id, Duration.ofMillis(timeoutMs));
            return timed(details, start, "제한 시간 안에 모두 완료되었다.");
        } catch (Exception e) {
            return timed(Map.of("error", e.getClass().getSimpleName()), start,
                    timeoutMs + "ms 안에 끝나지 않아 전체가 취소되었다. timeoutMs 값을 키워보라.");
        }
    }

    private Map<String, Object> timed(Object result, long startMillis, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", RequestContext.traceId());
        body.put("elapsedMs", System.currentTimeMillis() - startMillis);
        body.put("thread", Thread.currentThread().toString());
        body.put("virtual", Thread.currentThread().isVirtual());
        body.put("result", result);
        body.put("note", note);
        return body;
    }
}
