package com.example.vtdemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 패턴 ②: CPU 바운드와 I/O 바운드 executor를 분리한다.
 *
 * <p>가상 스레드 스케줄러는 <b>선점(preemption)을 하지 않는다.</b>
 * 블로킹 지점(I/O, sleep, lock 대기)을 만나야만 캐리어 스레드에서 내려온다.
 * 따라서 CPU만 계속 돌리는 작업을 가상 스레드에 태우면 캐리어를 붙잡은 채 놓지 않아
 * 다른 요청(가상 스레드)이 아예 실행 기회를 얻지 못한다.
 *
 * <p>실측(2코어 기준): CPU 작업 4개가 도는 동안 50ms짜리 I/O 작업 20개를 처리하면
 * <ul>
 *   <li>전부 가상 스레드에서 실행 → I/O 완료까지 <b>3004ms</b></li>
 *   <li>CPU는 고정 풀, I/O는 가상 스레드로 분리 → <b>53ms</b></li>
 * </ul>
 */
@Configuration
public class ExecutorConfig {

    /**
     * CPU 집약적 작업 전용 — 플랫폼 스레드 고정 풀(코어 수만큼).
     * OS가 선점 스케줄링을 해주므로 다른 요청 처리가 굶지 않는다.
     */
    @Bean("cpuBoundExecutor")
    public ExecutorService cpuBoundExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadFactory factory = Thread.ofPlatform()
                .name("cpu-bound-", 0)
                .daemon(true)
                .factory();
        return Executors.newFixedThreadPool(cores, factory);
    }

    /**
     * I/O 바운드 작업 전용 — 작업당 가상 스레드 하나.
     * 절대 고정 크기로 "풀링"하지 않는다. (풀링하면 확장성이라는 장점이 사라진다)
     */
    @Bean("ioBoundExecutor")
    public ExecutorService ioBoundExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
