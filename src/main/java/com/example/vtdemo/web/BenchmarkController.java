package com.example.vtdemo.web;

import com.example.vtdemo.report.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * 애플리케이션 안에서 바로 돌려볼 수 있는 비교 실험용 엔드포인트.
 *
 * <ul>
 *   <li>{@code GET /api/bench/pooling}   — 가상 스레드 풀링(안티패턴) vs 작업당 생성</li>
 *   <li>{@code GET /api/bench/platform-vs-virtual} — 플랫폼 스레드 풀 vs 가상 스레드</li>
 *   <li>{@code GET /api/bench/cpu-report?workUnits=200} — CPU 작업을 전용 풀에 위임</li>
 * </ul>
 *
 * <p>운영 코드에 둘 만한 엔드포인트는 아니고, 수치를 눈으로 확인하기 위한 학습용이다.
 */
@RestController
@RequestMapping("/api/bench")
public class BenchmarkController {

    private final ReportService reportService;

    public BenchmarkController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 가상 스레드를 고정 풀에 가두면(안티패턴) 동시 실행 수가 풀 크기로 제한되어
     * 확장성이라는 장점이 사라진다.
     */
    @GetMapping("/pooling")
    public Map<String, Object> pooling(@RequestParam(defaultValue = "2000") int tasks,
                                       @RequestParam(defaultValue = "100") int blockingMs,
                                       @RequestParam(defaultValue = "100") int poolSize) {

        long pooled = runBlockingTasks(
                Executors.newFixedThreadPool(poolSize, Thread.ofVirtual().factory()), tasks, blockingMs);
        long perTask = runBlockingTasks(
                Executors.newVirtualThreadPerTaskExecutor(), tasks, blockingMs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", tasks);
        body.put("blockingMsPerTask", blockingMs);
        body.put("pooledVirtualThreadsMs", pooled);
        body.put("threadPerTaskMs", perTask);
        body.put("speedup", String.format("%.1fx", (double) pooled / Math.max(perTask, 1)));
        body.put("verdict", "newFixedThreadPool(n, Thread.ofVirtual().factory()) 는 쓰지 말 것. "
                + "가상 스레드 생성 비용은 수 마이크로초라 재사용할 이유가 없다.");
        return body;
    }

    /** 전통적인 플랫폼 스레드 풀과 가상 스레드의 처리량 비교. */
    @GetMapping("/platform-vs-virtual")
    public Map<String, Object> platformVsVirtual(@RequestParam(defaultValue = "2000") int tasks,
                                                 @RequestParam(defaultValue = "100") int blockingMs,
                                                 @RequestParam(defaultValue = "200") int poolSize) {

        long platform = runBlockingTasks(Executors.newFixedThreadPool(poolSize), tasks, blockingMs);
        long virtual = runBlockingTasks(Executors.newVirtualThreadPerTaskExecutor(), tasks, blockingMs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", tasks);
        body.put("platformPoolMs", platform);
        body.put("virtualMs", virtual);
        body.put("speedup", String.format("%.1fx", (double) platform / Math.max(virtual, 1)));
        body.put("note", "I/O 바운드 작업일 때의 이야기다. CPU 바운드에서는 개선이 거의 없다.");
        return body;
    }

    /** CPU 집약적 작업 — 가상 스레드가 아니라 전용 플랫폼 스레드 풀에서 실행된다. */
    @GetMapping("/cpu-report")
    public ReportService.ReportResult cpuReport(@RequestParam(defaultValue = "200") int workUnits)
            throws Exception {
        return reportService.generate(workUnits);
    }

    private long runBlockingTasks(ExecutorService executor, int tasks, int blockingMs) {
        long start = System.currentTimeMillis();
        try (executor) {
            IntStream.range(0, tasks).forEach(i -> executor.submit(() -> {
                try {
                    Thread.sleep(blockingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        return System.currentTimeMillis() - start;
    }
}
