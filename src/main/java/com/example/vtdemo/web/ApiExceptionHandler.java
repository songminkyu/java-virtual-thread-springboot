package com.example.vtdemo.web;

import com.example.vtdemo.context.RequestContext;
import com.example.vtdemo.order.OrderRepository;
import com.example.vtdemo.order.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외를 ProblemDetail(RFC 9457) 형태로 변환한다.
 *
 * <p>커넥션 획득 타임아웃을 503으로 내려주므로, 세마포어 없는 엔드포인트에 부하를 주면
 * 응답으로 바로 확인할 수 있다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderRepository.ConnectionTimeoutException.class)
    public ProblemDetail handleConnectionTimeout(OrderRepository.ConnectionTimeoutException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("커넥션 풀 고갈");
        problem.setProperty("traceId", RequestContext.traceId());
        problem.setProperty("hint", "세마포어로 동시 접근을 제한하면 이 오류를 막을 수 있다. OrderService 참고.");
        return problem;
    }

    @ExceptionHandler(OrderService.OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderService.OrderNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("주문 없음");
        problem.setProperty("traceId", RequestContext.traceId());
        return problem;
    }
}
