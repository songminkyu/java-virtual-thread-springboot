package com.example.vtdemo.order;

import com.example.vtdemo.external.ExternalClients;

import java.time.Duration;

/**
 * 여러 외부 서비스를 동시에 호출해 하나로 합치는 기능.
 *
 * <p>구현이 두 가지 있고, 스프링 프로파일로 갈아끼운다.
 * <ul>
 *   <li>{@link ExecutorOrderAggregationService} — <b>기본값.</b> 표준 API만 사용해
 *       preview 플래그가 전혀 필요 없다.</li>
 *   <li>{@code preview.StructuredOrderAggregationService} — {@code StructuredTaskScope}(JEP 505) 사용.
 *       {@code -PenablePreview} 로 빌드하고 {@code preview} 프로파일을 켰을 때만 올라온다.</li>
 * </ul>
 *
 * <p>둘의 동작과 성능은 사실상 같다. 차이는 "취소 전파를 직접 짜야 하는가"이며,
 * 자세한 비교는 README 를 참고.
 */
public interface OrderAggregator {

    /** 주문/결제/배송을 동시에 조회해 합친다. 소요 시간은 가장 느린 하나 수준. */
    OrderDetails getOrderDetails(long orderId) throws Exception;

    /** 하나가 실패하면 나머지 형제 작업이 즉시 취소되는지 보여주는 시연용. */
    OrderDetails getOrderDetailsWithFailure(long orderId) throws Exception;

    /** 여러 리전 중 가장 먼저 성공한 응답만 채택한다. */
    ExternalClients.OrderInfo getOrderFromFastestReplica(long orderId) throws Exception;

    /** 제한 시간 안에 못 끝내면 전부 취소한다. */
    OrderDetails getOrderDetailsWithTimeout(long orderId, Duration timeout) throws Exception;

    /** 현재 활성화된 구현 이름 (응답에 표시해 어느 쪽이 도는지 확인용). */
    String implementationName();
}
