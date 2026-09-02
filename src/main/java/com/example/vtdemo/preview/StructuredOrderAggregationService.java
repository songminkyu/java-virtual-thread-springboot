package com.example.vtdemo.preview;

import com.example.vtdemo.external.ExternalClients;
import com.example.vtdemo.order.OrderAggregator;
import com.example.vtdemo.order.OrderDetails;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * 구조적 동시성(JEP 505)을 사용하는 구현 — <b>선택 사항</b>.
 *
 * <p>이 패키지({@code com.example.vtdemo.preview})는 기본 빌드에서 <b>제외</b>된다.
 * 쓰려면 두 가지를 켜야 한다:
 *
 * <pre>
 * ./gradlew bootRun -PenablePreview --args='--spring.profiles.active=preview'
 * ./gradlew test    -PenablePreview -Dspring.profiles.active=preview
 * </pre>
 *
 * <p>⚠️ <b>JDK 25에서 API가 바뀌었다.</b> 블로그·문서에 흔한 JDK 21 시절 코드
 * ({@code new StructuredTaskScope.ShutdownOnFailure()} + {@code throwIfFailed()})는
 * JDK 25에서 컴파일되지 않는다. IDE에서 {@code Joiner cannot be resolved} 가 뜬다면
 * IDE가 JDK 21~23으로 컴파일하고 있다는 뜻이다 (Joiner 는 JDK 24부터).
 *
 * <p>{@link com.example.vtdemo.order.ExecutorOrderAggregationService} 와 비교해 보면
 * 취소 전파와 ScopedValue 상속을 스코프가 대신 해주기 때문에 코드가 훨씬 짧다.
 */
@Service
@Profile("preview")
public class StructuredOrderAggregationService implements OrderAggregator {

    private final ExternalClients clients;

    public StructuredOrderAggregationService(ExternalClients clients) {
        this.clients = clients;
    }

    @Override
    public String implementationName() {
        return "StructuredOrderAggregationService (StructuredTaskScope, preview 필요)";
    }

    @Override
    public OrderDetails getOrderDetails(long orderId) throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            var order = scope.fork(() -> clients.getOrder(orderId));
            var payment = scope.fork(() -> clients.getPayment(orderId));
            var shipping = scope.fork(() -> clients.getShipping(orderId));

            scope.join(); // 실패 시 FailedException + 형제 작업 자동 취소

            return new OrderDetails(order.get(), payment.get(), shipping.get());
        }
    }

    @Override
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

    @Override
    public ExternalClients.OrderInfo getOrderFromFastestReplica(long orderId) throws Exception {
        try (var scope = StructuredTaskScope.open(
                Joiner.<ExternalClients.OrderInfo>anySuccessfulResultOrThrow())) {

            scope.fork(() -> clients.getOrder(orderId));    // 300ms
            scope.fork(() -> {                               // 100ms — 이쪽이 이긴다
                Thread.sleep(100);
                return new ExternalClients.OrderInfo(orderId, "CONFIRMED(replica)");
            });

            return scope.join();
        }
    }

    @Override
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
}
