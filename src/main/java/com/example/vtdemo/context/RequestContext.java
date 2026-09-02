package com.example.vtdemo.context;

/**
 * 패턴 ③: ThreadLocal 대신 ScopedValue (JDK 25 정식 기능, JEP 506).
 *
 * <p>가상 스레드는 요청마다 새로 만들어지고 수만 개까지 늘어난다.
 * ThreadLocal은 스레드마다 해시맵을 들고 있어 메모리를 먹고, {@code remove()}를 빠뜨리면
 * (특히 풀링된 스레드에서) 이전 요청의 값이 다음 요청에 그대로 보이는 사고가 난다.
 *
 * <p>ScopedValue는 불변이고 바인딩된 블록을 벗어나면 자동으로 해제되므로 그런 누수가
 * 구조적으로 불가능하다. 또 {@code StructuredTaskScope}로 fork한 하위 작업에 자동 상속되어
 * 트레이스 ID 전파에 딱 맞는다.
 *
 * <p>바인딩은 {@link com.example.vtdemo.web.RequestContextFilter}가 요청마다 수행한다.
 */
public final class RequestContext {

    /** 요청 추적용 트레이스 ID. */
    public static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

    /** 요청을 보낸 사용자 ID. */
    public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();

    private RequestContext() {
    }

    /** 바인딩되지 않은 곳(예: 배치 작업)에서 호출해도 예외가 나지 않도록 기본값을 준다. */
    public static String traceId() {
        return TRACE_ID.orElse("no-trace");
    }

    public static String userId() {
        return USER_ID.orElse("anonymous");
    }
}
