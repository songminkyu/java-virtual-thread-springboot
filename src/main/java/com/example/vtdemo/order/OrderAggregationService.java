package com.example.vtdemo.order;

import com.example.vtdemo.external.ExternalClients;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * 패턴 ⑤: 구조적 동시성(Structured Concurrency)으로 여러 서비스를 동시에 호출한다.
 *
 * <p>⚠️ <b>JDK 25에서 API가 바뀌었다.</b> 블로그·문서에 흔히 보이는 JDK 21 시절 코드
 * ({@code new StructuredTaskScope.ShutdownOnFailure()} + {@code throwIfFailed()})는
 * JDK 25에서 <b>컴파일되지 않는다.</b> 지금은 {@code StructuredTaskScope.open(...)} 과
 * "합류 정책"인 {@code Joiner}로 통합되었다.
 *
 * <p>아직 preview 기능(JEP 505)이라 {@code --enable-preview} 가 필요하다.
 * (build.gradle 에 이미 설정해 두었다)
 */
@Service
public class OrderAggregationService {

    private final ExternalClients clients;

    public OrderAggregationService(ExternalClients clients) {
        this.clients = clients;
    }

    /**
     * 주문/결제/배송 정보를 동시에 조회해 하나로 합친다.
     * 소요 시간은 세 호출의 합이 아니라 <b>가장 느린 하나</b> 수준이다. (1200ms → 약 500ms)
     *
     * <p>기본 {@code open()}은 "전부 성공할 때까지 대기, 하나라도 실패하면 나머지 자동 취소"이며
     * 하위 작업들의 반환 타입이 서로 달라도 된다.
     */
    public OrderDetails getOrderDetails(long orderId) throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            var order = scope.fork(() -> clients.getOrder(orderId));
            var payment = scope.fork(() -> clients.getPayment(orderId));
            var shipping = scope.fork(() -> clients.getShipping(orderId));

            scope.join(); // 실패 시 FailedException, 형제 작업은 자동 취소

            return new OrderDetails(order.get(), payment.get(), shipping.get());
        }
    }

    /**
     * 하나가 실패하면 나머지가 즉시 취소되는지 확인하는 시연용 메서드.
     * 배송 조회가 3초 걸리도록 두었지만, 100ms 만에 실패하는 결제 조회 때문에 곧바로 정리된다.
     */
    public OrderDetails getOrderDetailsWithFailure(long orderId) throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            var order = scope.fork(() -> clients.getOrder(orderId));
            var payment = scope.fork(() -> clients.getPaymentThatFails(orderId));
            var shipping = scope.fork(() -> {
                Thread.sleep(3000); // 취소되지 않으면 3초를 기다려야 한다
                return clients.getShipping(orderId);
            });

            scope.join();

            return new OrderDetails(order.get(), payment.get(), shipping.get());
        }
    }

    /**
     * 여러 리전 중 <b>가장 먼저 성공한</b> 응답만 채택한다.
     * {@code Joiner.anySuccessfulResultOrThrow()} 를 쓰면 join()이 그 결과를 바로 반환한다.
     */
    public ExternalClients.OrderInfo getOrderFromFastestReplica(long orderId) throws Exception {
        try (var scope = StructuredTaskScope.open(
                Joiner.<ExternalClients.OrderInfo>anySuccessfulResultOrThrow())) {

            scope.fork(() -> clients.getOrder(orderId));       // 300ms
            scope.fork(() -> {                                  // 100ms — 이쪽이 이긴다
                Thread.sleep(100);
                return new ExternalClients.OrderInfo(orderId, "CONFIRMED(replica)");
            });

            return scope.join();
        }
    }

    /**
     * 스코프 전체에 타임아웃을 건다. 제한 시간 안에 못 끝내면 TimeoutException 과 함께 전부 취소된다.
     */
    public OrderDetails getOrderDetailsWithTimeout(long orderId, Duration timeout) throws Exception {
        try (var scope = StructuredTaskScope.open(
                Joiner.<Object>awaitAllSuccessfulOrThrow(),
                cf -> cf.withTimeout(timeout))) {

            var order = scope.fork(() -> clients.getOrder(orderId));
            var payment = scope.fork(() -> clients.getPayment(orderId));
            var shipping = scope.fork(() -> clients.getShipping(orderId));

            scope.join();

            return new OrderDetails(order.get(), payment.get(), shipping.get());
        }
    }

    public record OrderDetails(ExternalClients.OrderInfo order,
                               ExternalClients.PaymentInfo payment,
                               ExternalClients.ShippingInfo shipping) {
    }
}
