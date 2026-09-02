package com.example.vtdemo.external;

import com.example.vtdemo.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 외부 서비스 호출을 흉내 낸 클라이언트들.
 *
 * <p>실무에서는 RestClient / WebClient / Feign 등이 들어갈 자리다.
 * 어느 쪽이든 <b>블로킹 I/O</b>이므로 가상 스레드가 캐리어를 반납하고,
 * 그래서 동기식 코드 그대로 두어도 확장성이 나온다.
 *
 * <p>각 메서드는 {@code RequestContext.traceId()}를 읽는다. ScopedValue는
 * {@code StructuredTaskScope}로 fork한 하위 작업에도 자동 상속되므로,
 * 트레이스 ID를 파라미터로 넘기지 않아도 로그에 그대로 찍힌다.
 */
@Component
public class ExternalClients {

    private static final Logger log = LoggerFactory.getLogger(ExternalClients.class);

    private final long orderLatencyMs;
    private final long paymentLatencyMs;
    private final long shippingLatencyMs;

    public ExternalClients(@Value("${app.external.order-latency-ms:300}") long orderLatencyMs,
                           @Value("${app.external.payment-latency-ms:500}") long paymentLatencyMs,
                           @Value("${app.external.shipping-latency-ms:400}") long shippingLatencyMs) {
        this.orderLatencyMs = orderLatencyMs;
        this.paymentLatencyMs = paymentLatencyMs;
        this.shippingLatencyMs = shippingLatencyMs;
    }

    public OrderInfo getOrder(long orderId) throws InterruptedException {
        call("order-service", orderLatencyMs);
        return new OrderInfo(orderId, "CONFIRMED");
    }

    public PaymentInfo getPayment(long orderId) throws InterruptedException {
        call("payment-service", paymentLatencyMs);
        return new PaymentInfo(orderId, "PAID", 19_800);
    }

    public ShippingInfo getShipping(long orderId) throws InterruptedException {
        call("shipping-service", shippingLatencyMs);
        return new ShippingInfo(orderId, "IN_TRANSIT", "1234-5678-9012");
    }

    /** 장애 상황 시연용 — 항상 실패한다. */
    public PaymentInfo getPaymentThatFails(long orderId) throws InterruptedException {
        Thread.sleep(100);
        throw new IllegalStateException("payment-service 장애 (orderId=" + orderId + ")");
    }

    private void call(String service, long latencyMs) throws InterruptedException {
        log.info("[{}] {} 호출 시작 (thread={})", RequestContext.traceId(), service, Thread.currentThread());
        Thread.sleep(latencyMs); // 블로킹 I/O → 여기서 unmount
    }

    public record OrderInfo(long orderId, String status) {
    }

    public record PaymentInfo(long orderId, String status, int amount) {
    }

    public record ShippingInfo(long orderId, String status, String trackingNumber) {
    }
}
