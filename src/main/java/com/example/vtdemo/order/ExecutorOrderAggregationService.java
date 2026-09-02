package com.example.vtdemo.order;

import com.example.vtdemo.context.RequestContext;
import com.example.vtdemo.external.ExternalClients;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 여러 서비스 동시 호출 — <b>preview 기능 없이</b> 표준 API만 사용한 구현. (기본값)
 *
 * <p>{@code StructuredTaskScope}(JEP 505)는 JDK 25에서 아직 preview라
 * {@code --enable-preview} 와 IDE 설정이 필요하다. 그게 부담스러운 환경을 위해
 * 같은 동작을 {@link ExecutorService} 계열 표준 API로 구현했다.
 *
 * <table border="1">
 *   <caption>대응 관계</caption>
 *   <tr><th>시나리오</th><th>StructuredTaskScope</th><th>여기서 쓰는 표준 API</th></tr>
 *   <tr><td>전부 취합</td><td>{@code open()} + {@code join()}</td><td>{@code ExecutorCompletionService}</td></tr>
 *   <tr><td>실패 시 형제 취소</td><td>자동</td><td>{@code cancel(true)} 직접 호출</td></tr>
 *   <tr><td>가장 빠른 응답</td><td>{@code Joiner.anySuccessfulResultOrThrow()}</td><td>{@code invokeAny(...)}</td></tr>
 *   <tr><td>전체 타임아웃</td><td>{@code cf.withTimeout(...)}</td><td>{@code invokeAll(tasks, timeout, unit)}</td></tr>
 *   <tr><td>ScopedValue 전파</td><td>자동 상속</td><td>직접 꺼내 재바인딩</td></tr>
 * </table>
 *
 * <p>가상 스레드 자체는 정식 기능이므로 확장성 이점은 그대로다. 잃는 것은 스코프가 대신
 * 해주던 취소 전파와 컨텍스트 상속뿐이고, 이 클래스가 그걸 손으로 처리한다.
 */
@Service
@Profile("!preview")
public class ExecutorOrderAggregationService implements OrderAggregator {

    private final ExternalClients clients;

    public ExecutorOrderAggregationService(ExternalClients clients) {
        this.clients = clients;
    }

    @Override
    public String implementationName() {
        return "ExecutorOrderAggregationService (표준 API, preview 불필요)";
    }

    @Override
    public OrderDetails getOrderDetails(long orderId) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Object> results = allSuccessfulOrCancel(executor, List.of(
                    traced(() -> clients.getOrder(orderId)),
                    traced(() -> clients.getPayment(orderId)),
                    traced(() -> clients.getShipping(orderId))));

            return toDetails(results);
        }
    }

    @Override
    public OrderDetails getOrderDetailsWithFailure(long orderId) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Object> results = allSuccessfulOrCancel(executor, List.of(
                    traced(() -> clients.getOrder(orderId)),
                    traced(() -> clients.getPaymentThatFails(orderId)),   // 100ms 뒤 실패
                    traced(() -> {                                        // 취소되지 않으면 3초
                        Thread.sleep(3000);
                        return clients.getShipping(orderId);
                    })));

            return toDetails(results);
        }
    }

    @Override
    public ExternalClients.OrderInfo getOrderFromFastestReplica(long orderId) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // invokeAny: 첫 성공 결과를 반환하고 나머지는 알아서 취소한다.
            // traced(...) 가 Callable<Object> 라서 반환값은 캐스팅해서 받는다.
            Object winner = executor.invokeAny(List.of(
                    traced(() -> clients.getOrder(orderId)),               // 300ms
                    traced(() -> {                                         // 100ms — 이쪽이 이긴다
                        Thread.sleep(100);
                        return new ExternalClients.OrderInfo(orderId, "CONFIRMED(replica)");
                    })));
            return (ExternalClients.OrderInfo) winner;
        }
    }

    @Override
    public OrderDetails getOrderDetailsWithTimeout(long orderId, Duration timeout) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // invokeAll(tasks, timeout, unit): 제한 시간이 지나면 끝나지 않은 작업을 취소하고,
            // 결과는 제출한 순서 그대로 돌려준다.
            List<Future<Object>> futures = executor.invokeAll(List.of(
                            traced(() -> clients.getOrder(orderId)),
                            traced(() -> clients.getPayment(orderId)),
                            traced(() -> clients.getShipping(orderId))),
                    timeout.toMillis(), TimeUnit.MILLISECONDS);

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get()); // 취소된 작업은 CancellationException 을 던진다
            }
            return toDetails(results);
        }
    }

    /**
     * 전부 성공하면 <b>제출 순서대로</b> 결과를 돌려주고,
     * 하나라도 실패하면 남은 형제 작업을 즉시 취소한 뒤 예외를 던진다.
     *
     * <p>{@code StructuredTaskScope} 의 기본 동작을 손으로 구현한 것이다.
     * 먼저 끝난 것부터 회수해야 "즉시" 실패를 감지할 수 있으므로
     * {@link ExecutorCompletionService} 를 쓰고, 순서를 되살리기 위해 인덱스를 함께 넘긴다.
     */
    private List<Object> allSuccessfulOrCancel(ExecutorService executor, List<Callable<Object>> tasks)
            throws ExecutionException, InterruptedException {

        var completionService = new ExecutorCompletionService<Indexed>(executor);
        List<Future<Indexed>> futures = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            int index = i;
            Callable<Object> task = tasks.get(i);
            futures.add(completionService.submit(() -> new Indexed(index, task.call())));
        }

        Object[] ordered = new Object[tasks.size()];
        try {
            for (int i = 0; i < tasks.size(); i++) {
                Indexed done = completionService.take().get(); // 먼저 끝난 것부터
                ordered[done.index()] = done.value();
            }
        } catch (ExecutionException | InterruptedException | CancellationException e) {
            futures.forEach(future -> future.cancel(true)); // ← 스코프가 해주던 일
            throw e;
        }
        return List.of(ordered);
    }

    /**
     * ScopedValue 는 {@code executor.submit()} 으로 만든 가상 스레드에 <b>상속되지 않는다.</b>
     * (StructuredTaskScope 로 fork 하면 자동 상속된다)
     * 그래서 바깥에서 트레이스 ID를 꺼내 두었다가 작업 안에서 다시 바인딩해 준다.
     */
    private Callable<Object> traced(Callable<Object> task) {
        String traceId = RequestContext.traceId();
        String userId = RequestContext.userId();
        return () -> ScopedValue.where(RequestContext.TRACE_ID, traceId)
                .where(RequestContext.USER_ID, userId)
                .call(task::call);
    }

    private OrderDetails toDetails(List<Object> results) {
        return new OrderDetails(
                (ExternalClients.OrderInfo) results.get(0),
                (ExternalClients.PaymentInfo) results.get(1),
                (ExternalClients.ShippingInfo) results.get(2));
    }

    /** 완료 순서와 무관하게 원래 순서를 복원하기 위한 인덱스 래퍼. */
    private record Indexed(int index, Object value) {
    }
}
