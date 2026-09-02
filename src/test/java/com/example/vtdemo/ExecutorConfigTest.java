package com.example.vtdemo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU 바운드 / I/O 바운드 executor 빈이 의도대로 만들어졌는지 검증한다.
 */
@SpringBootTest
class ExecutorConfigTest {

    @Autowired
    @Qualifier("ioBoundExecutor")
    ExecutorService ioBoundExecutor;

    @Autowired
    @Qualifier("cpuBoundExecutor")
    ExecutorService cpuBoundExecutor;

    @Test
    @DisplayName("ioBoundExecutor는 가상 스레드를 만든다")
    void ioBoundExecutorCreatesVirtualThreads() throws Exception {
        boolean isVirtual = ioBoundExecutor.submit(() -> Thread.currentThread().isVirtual()).get();
        assertTrue(isVirtual, "I/O 전용 executor는 가상 스레드를 써야 한다");
    }

    @Test
    @DisplayName("cpuBoundExecutor는 플랫폼 스레드를 만든다")
    void cpuBoundExecutorCreatesPlatformThreads() throws Exception {
        boolean isVirtual = cpuBoundExecutor.submit(() -> Thread.currentThread().isVirtual()).get();
        assertFalse(isVirtual, "CPU 전용 executor는 플랫폼 스레드를 써야 한다 (선점 스케줄링이 필요)");

        String name = cpuBoundExecutor.submit(() -> Thread.currentThread().getName()).get();
        assertTrue(name.startsWith("cpu-bound-"), "스레드 이름으로 용도를 구분할 수 있어야 한다: " + name);
    }
}
