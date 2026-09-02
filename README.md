# 가상 스레드 프로덕션 패턴 — Spring Boot 샘플

앞서 만든 순수 Java 샘플을 **Spring Boot 애플리케이션 형태로** 옮긴 것입니다.
각 패턴이 REST 엔드포인트와 테스트로 들어가 있습니다.

참고 글: https://dev-post.com/java-virtual-threads-production-patterns/

## 스택

| 항목 | 버전 | 비고 |
|---|---|---|
| Spring Boot | **4.1.1** | 2026-09 기준 최신 안정 버전 (4.2.0-M1은 마일스톤이라 제외) |
| Java | **25** | Boot 4.1.1은 Java 17~26 지원 |
| Gradle | **8.14.3** (wrapper 포함) | Boot 4.1.1 요구사항: Gradle 8.14+ 또는 9.x |
| 빌드 | Gradle Groovy DSL | `build.gradle` |

## 실행 — preview 플래그가 필요 없습니다

```bash
./gradlew bootRun          # 애플리케이션 실행 (http://localhost:8080)
./gradlew test             # 테스트 실행
./gradlew build            # 빌드
java -jar build/libs/virtual-thread-springboot-0.0.1-SNAPSHOT.jar
```

기본 빌드는 **표준 API만** 사용하므로 `--enable-preview` 도, IDE 추가 설정도 필요 없습니다.
(`ScopedValue` 는 JDK 25 정식 기능이라 그대로 씁니다)

### StructuredTaskScope 버전도 보고 싶다면 (선택)

```bash
./gradlew bootRun -PenablePreview --args='--spring.profiles.active=preview'
./gradlew test    -PenablePreview
java --enable-preview -jar build/libs/virtual-thread-springboot-0.0.1-SNAPSHOT.jar \
     --spring.profiles.active=preview
```

`-PenablePreview` 를 주면 `com.example.vtdemo.preview` 패키지가 컴파일에 포함되고
필요한 플래그가 자동으로 붙습니다. 주지 않으면 그 패키지는 아예 빌드에서 제외됩니다.

> **IDE에서 `StructuredTaskScope.Joiner cannot be resolved` 가 뜬다면**
> IDE가 JDK 25가 아니라 JDK 21~23으로 컴파일하고 있다는 뜻입니다.
> `StructuredTaskScope` 자체는 JDK 21에도 있지만 `Joiner` 는 JDK 24부터 생긴 타입이라
> 그것만 못 찾습니다. 프로젝트 JDK를 25로 바꾸고 preview 를 켜거나,
> 그냥 기본 빌드(preview 미사용)를 쓰면 됩니다.

## 핵심 설정 — 이 한 줄

```yaml
# src/main/resources/application.yml
spring:
  threads:
    virtual:
      enabled: true
```

이것만으로 Tomcat이 요청마다 가상 스레드를 사용합니다.
**나머지 패턴은 애플리케이션 코드에서 따로 챙겨야 하며**, 그게 이 프로젝트의 내용입니다.

## 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/thread-info` | 요청이 가상 스레드에서 처리되는지, 블로킹 전후 캐리어 스레드가 바뀌는지 확인 |
| GET | `/api/orders/{id}` | 세마포어로 커넥션 풀을 보호한 조회 (**권장 패턴**) |
| GET | `/api/orders/{id}/unprotected` | 세마포어 없는 조회 — 부하를 주면 503(커넥션 고갈) |
| GET | `/api/orders/{id}/details` | 구조적 동시성으로 3개 서비스 동시 호출 (1200ms → 약 500ms) |
| GET | `/api/orders/{id}/details-fail` | 하나 실패 시 3초짜리 형제 작업이 즉시 취소되는지 |
| GET | `/api/orders/{id}/fastest` | 가장 먼저 성공한 리전의 응답만 채택 |
| GET | `/api/orders/{id}/details-timeout?timeoutMs=300` | 스코프 전체 타임아웃 |
| GET | `/api/bench/pooling` | 가상 스레드 풀링(안티패턴) vs 작업당 생성 |
| GET | `/api/bench/platform-vs-virtual` | 플랫폼 스레드 풀 vs 가상 스레드 |
| GET | `/api/bench/cpu-report?workUnits=200` | CPU 작업을 전용 플랫폼 스레드 풀에 위임 |

빠르게 확인해 보기:

```bash
curl -s localhost:8080/api/thread-info | jq
curl -s localhost:8080/api/orders/1/details | jq
curl -s "localhost:8080/api/bench/pooling?tasks=2000" | jq

# 세마포어 없는 엔드포인트에 부하 → 503 발생
ab -n 500 -c 200 http://localhost:8080/api/orders/1/unprotected
```

## 프로젝트 구조와 패턴 매핑

```
src/main/java/com/example/vtdemo/
├── config/ExecutorConfig.java          # 패턴② CPU/IO executor 분리
├── context/RequestContext.java         # 패턴③ ScopedValue 정의
├── web/RequestContextFilter.java       # 패턴③ 요청마다 ScopedValue 바인딩 (MDC 대체)
├── web/ThreadInfoController.java       # 패턴① Mount/Unmount 확인
├── order/OrderRepository.java          # HikariCP 흉내 (커넥션 유한)
├── order/OrderService.java             # 패턴④ 세마포어로 커넥션 풀 보호
├── order/OrderAggregator.java          # 패턴⑤ 동시 호출 인터페이스
├── order/ExecutorOrderAggregationService.java   #   └ 기본 구현 (표준 API, preview 불필요)
├── preview/StructuredOrderAggregationService.java # └ 선택 구현 (-PenablePreview 시에만 빌드)
├── report/ReportService.java           # CPU 작업을 cpuBoundExecutor 에 위임
└── web/BenchmarkController.java        # 풀링 안티패턴 등 비교 실험
```

### 패턴 ① Mount / Unmount

가상 스레드의 `toString()`이 `VirtualThread[#26]/runnable@ForkJoinPool-1-worker-1` 형태라
`@` 뒤가 현재 올라타 있는 캐리어 스레드입니다. `/api/thread-info` 는 블로킹 전후의 캐리어를
응답에 담아, 요청 처리 도중 unmount 됐다가 다른 캐리어에 다시 올라타는 것을 보여줍니다.

### 패턴 ② CPU / IO executor 분리

가상 스레드 스케줄러는 **선점하지 않습니다.** 블로킹 지점을 만나야만 캐리어에서 내려오므로,
CPU만 도는 작업은 캐리어를 붙잡은 채 놓지 않고 그동안 다른 요청이 굶습니다.

```java
@Bean("cpuBoundExecutor")   // 플랫폼 스레드 고정 풀 (OS가 선점 스케줄링)
ExecutorService cpuBoundExecutor() { return Executors.newFixedThreadPool(cores); }

@Bean("ioBoundExecutor")    // 작업당 가상 스레드
ExecutorService ioBoundExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }
```

앞선 순수 Java 실측(2코어): CPU 작업이 도는 동안 I/O 완료까지 **3004ms → 53ms**.

### 패턴 ③ ThreadLocal → ScopedValue

`RequestContextFilter`가 요청마다 트레이스 ID를 바인딩하고, 컨트롤러·서비스는 파라미터로
받지 않고 `RequestContext.traceId()`로 읽습니다. 블록을 벗어나면 자동 해제되므로
`remove()`가 필요 없고, `StructuredTaskScope`로 fork한 하위 작업에도 자동 상속됩니다.

| | ThreadLocal | ScopedValue |
|---|---|---|
| 변경가능성 | mutable | immutable |
| 메모리 관리 | 수동 `remove()` | 블록 종료 시 자동 |
| 하위 작업 전파 | InheritableThreadLocal(복사) | fork 시 자동 상속 |

### 패턴 ④ 세마포어로 커넥션 풀 보호

가상 스레드는 수만 개를 만들어도 되지만 **DB 커넥션은 10~20개뿐**입니다.
스레드 수를 줄이는 게 아니라 커넥션을 쓰는 구간만 제한합니다.

```java
private final Semaphore dbLimiter = new Semaphore(poolSize); // Hikari maximumPoolSize 와 일치

public Order findOrder(long id) throws InterruptedException {
    dbLimiter.acquire();
    try { return orderRepository.findById(id).orElseThrow(); }
    finally { dbLimiter.release(); }
}
```

검증 결과(커넥션 5개 풀에 요청 100개): 세마포어 적용 시 **성공 100 / 실패 0**,
미적용 경로는 커넥션 획득 타임아웃 발생.

### 패턴 ⑤ 여러 서비스 동시 호출 — 구현이 두 가지

`OrderAggregator` 인터페이스에 구현이 둘 있고, 스프링 프로파일로 갈아끼웁니다.
**동작과 성능은 사실상 같습니다.** 같은 단언으로 두 구현을 모두 검증했고 결과도 같습니다:

| | 표준 API (기본) | StructuredTaskScope (선택) |
|---|---|---|
| fan-out | 532ms | 509ms |
| 실패 시 형제 취소 | 106ms | 108ms |
| 가장 빠른 응답 채택 | 104ms | 108ms |
| 타임아웃(200ms) | 204ms | 204ms |

#### 기본: `ExecutorOrderAggregationService` (preview 불필요)

| 시나리오 | 사용하는 표준 API |
|---|---|
| 전부 취합 + 실패 시 취소 | `ExecutorCompletionService` + `cancel(true)` |
| 가장 빠른 응답 채택 | `executor.invokeAny(...)` |
| 전체 타임아웃 | `executor.invokeAll(tasks, timeout, unit)` |
| ScopedValue 전파 | 직접 꺼내서 재바인딩 (`traced(...)` 헬퍼) |

#### 선택: `preview/StructuredOrderAggregationService`

```java
try (var scope = StructuredTaskScope.open()) {   // 기본: 전부 성공 대기 + 실패 시 형제 자동 취소
    var order    = scope.fork(() -> clients.getOrder(id));
    var payment  = scope.fork(() -> clients.getPayment(id));
    var shipping = scope.fork(() -> clients.getShipping(id));
    scope.join();
    return new OrderDetails(order.get(), payment.get(), shipping.get());
}
```

취소 전파와 ScopedValue 상속을 스코프가 대신 해주므로 코드가 훨씬 짧습니다.
그게 구조적 동시성으로 얻는 실질적 이득입니다.

⚠️ **JDK 25에서 API가 바뀌었습니다.** 블로그·문서에 흔한 JDK 21 시절 코드
(`new StructuredTaskScope.ShutdownOnFailure()` + `throwIfFailed()`)는 컴파일되지 않습니다.
`ShutdownOnFailure` / `ShutdownOnSuccess` 는 `Joiner` 라는 합류 정책으로 통합되었습니다.

| Joiner | 동작 |
|---|---|
| (기본 `open()`) | 전부 성공 대기, 하나 실패 시 나머지 자동 취소 |
| `anySuccessfulResultOrThrow()` | 가장 먼저 성공한 결과 하나만 채택 |
| `allSuccessfulOrThrow()` | 성공한 subtask 스트림 반환 (동일 타입일 때) |

타임아웃은 `open()`의 두 번째 인자로:

```java
StructuredTaskScope.open(Joiner.<Object>awaitAllSuccessfulOrThrow(),
                         cf -> cf.withTimeout(Duration.ofMillis(300)))
```

## 테스트

| 테스트 | 내용 |
|---|---|
| `VirtualThreadEnabledTest` | 실제 포트에 HTTP 요청을 보내 **가상 스레드에서 처리되는지**, 트레이스 ID가 전파되는지, 동시 요청 200개가 모두 성공하는지 |
| `ExecutorConfigTest` | `ioBoundExecutor`는 가상 스레드, `cpuBoundExecutor`는 플랫폼 스레드인지 |
| `OrderServiceSemaphoreTest` | 세마포어가 커넥션 풀을 보호하는지 / 없으면 고갈되는지 |
| `ExecutorOrderAggregationServiceTest` | fan-out, 실패 시 형제 취소, 가장 빠른 응답 채택, 타임아웃 (기본 구현) |
| `preview/StructuredOrderAggregationServiceTest` | 같은 단언으로 StructuredTaskScope 구현 검증 (`-PenablePreview` 필요) |

## 실제 DB를 붙일 때

`application.yml` 에 주석으로 넣어둔 설정을 켜고, 세마포어 크기를 풀 크기와 맞추면 됩니다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10       # ← OrderService 의 Semaphore 크기와 일치시킬 것
      connection-timeout: 3000
      leak-detection-threshold: 5000
```

## 가상 스레드가 맞는 곳 / 아닌 곳

| ✅ 적합 | ❌ 부적합 |
|---|---|
| DB 쿼리가 많은 서비스 | CPU 집약적 연산 |
| 외부 API 호출 | 배치 계산 |
| 파일 I/O | 이미지 프로세싱 |
| Spring MVC REST API | 복잡한 수치 계산 |

## 참고

- [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506)
- [JEP 505: Structured Concurrency (Fifth Preview)](https://openjdk.org/jeps/505)
