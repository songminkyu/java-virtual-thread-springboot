package com.example.vtdemo.web;

import com.example.vtdemo.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청마다 ScopedValue를 바인딩하는 필터 — MDC/ThreadLocal 기반 컨텍스트 전파의 대체재.
 *
 * <p>{@code ScopedValue.where(...).call(...)} 안에서 필터 체인을 호출하므로,
 * 컨트롤러·서비스 어디서든 파라미터로 넘기지 않고 {@code RequestContext.traceId()}로 값을 읽을 수 있다.
 * 요청 처리가 끝나 블록을 벗어나면 자동으로 해제되어 {@code remove()} 호출이 필요 없다.
 */
@Component
public class RequestContextFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String USER_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = header(request, TRACE_HEADER, () -> UUID.randomUUID().toString().substring(0, 8));
        String userId = header(request, USER_HEADER, () -> "anonymous");
        response.setHeader(TRACE_HEADER, traceId);

        try {
            ScopedValue.where(RequestContext.TRACE_ID, traceId)
                    .where(RequestContext.USER_ID, userId)
                    .call(() -> {
                        filterChain.doFilter(request, response);
                        return null;
                    });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static String header(HttpServletRequest request, String name,
                                 java.util.function.Supplier<String> fallback) {
        String value = request.getHeader(name);
        return (value == null || value.isBlank()) ? fallback.get() : value;
    }
}
