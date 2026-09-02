package com.example.vtdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java 25 가상 스레드 프로덕션 패턴 - Spring Boot 샘플.
 *
 * <p>핵심 설정은 application.yml 의 {@code spring.threads.virtual.enabled: true} 한 줄이며,
 * 그것만으로 Tomcat이 요청마다 가상 스레드를 사용한다.
 * 나머지 패턴(세마포어로 커넥션 풀 보호, CPU/IO executor 분리, ScopedValue, 구조적 동시성)은
 * 애플리케이션 코드에서 따로 챙겨야 하며, 이 프로젝트가 그 예시다.
 */
@SpringBootApplication
public class VirtualThreadSpringBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualThreadSpringBootApplication.class, args);
    }
}
