package com.example.vtdemo.report;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * CPU 집약적 작업은 가상 스레드가 아니라 <b>전용 플랫폼 스레드 풀</b>에서 돌린다.
 *
 * <p>요청 자체는 가상 스레드에서 처리되지만, 무거운 계산은 {@code cpuBoundExecutor}에
 * 넘기고 결과만 기다린다. 이렇게 하면
 * <ul>
 *   <li>계산이 캐리어 스레드를 점유하지 않아 다른 요청이 굶지 않고,</li>
 *   <li>동시에 도는 계산 개수가 코어 수로 제한되어 CPU 경합도 통제된다.</li>
 * </ul>
 *
 * <p>가상 스레드는 {@code Future.get()}에서 블로킹되지만, 그건 캐리어를 반납하는
 * "착한 블로킹"이라 문제가 되지 않는다.
 */
@Service
public class ReportService {

    private final ExecutorService cpuBoundExecutor;

    public ReportService(@Qualifier("cpuBoundExecutor") ExecutorService cpuBoundExecutor) {
        this.cpuBoundExecutor = cpuBoundExecutor;
    }

    /** 무거운 계산을 CPU 전용 풀에 위임한다. */
    public ReportResult generate(int workUnits) throws Exception {
        long start = System.currentTimeMillis();

        Future<Long> future = cpuBoundExecutor.submit(() -> {
            long checksum = 0;
            for (int i = 0; i < workUnits * 1_000_000; i++) {
                checksum += (long) Math.sqrt(i);
            }
            return checksum;
        });

        long checksum = future.get(); // 가상 스레드는 여기서 unmount 되어 캐리어를 반납한다
        String worker = "cpu-bound pool (" + Runtime.getRuntime().availableProcessors() + " threads)";

        return new ReportResult(checksum, System.currentTimeMillis() - start, worker);
    }

    public record ReportResult(long checksum, long elapsedMs, String executedOn) {
    }
}
