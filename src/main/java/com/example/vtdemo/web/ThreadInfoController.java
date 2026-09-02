package com.example.vtdemo.web;

import com.example.vtdemo.context.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 패턴 ①: Mount / Unmount 를 HTTP 응답으로 직접 확인한다.
 *
 * <p>가상 스레드의 {@code toString()}은
 * {@code VirtualThread[#26]/runnable@ForkJoinPool-1-worker-1} 형태이고 '@' 뒤가 캐리어 스레드다.
 * 블로킹 전후의 캐리어를 응답에 담으면, 요청 처리 도중 unmount 되었다가 다른 캐리어에
 * 다시 올라타는 것을 눈으로 볼 수 있다.
 *
 * <pre>
 * $ curl localhost:8080/api/thread-info
 * $ ab -n 200 -c 50 http://localhost:8080/api/thread-info   # 부하를 주면 캐리어 변경이 잘 보인다
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class ThreadInfoController {

    @GetMapping("/thread-info")
    public Map<String, Object> threadInfo() throws InterruptedException {
        Thread current = Thread.currentThread();
        String carrierBefore = carrierOf(current);

        // 블로킹 지점 — 여기서 가상 스레드가 캐리어를 반납(unmount)한다.
        Thread.sleep(50);

        String carrierAfter = carrierOf(Thread.currentThread());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", RequestContext.traceId());
        result.put("userId", RequestContext.userId());
        result.put("thread", current.toString());
        result.put("virtual", current.isVirtual());
        result.put("carrierBeforeBlocking", carrierBefore);
        result.put("carrierAfterBlocking", carrierAfter);
        result.put("carrierChanged", !carrierBefore.equals(carrierAfter));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("note", current.isVirtual()
                ? "spring.threads.virtual.enabled=true 가 동작 중이다."
                : "플랫폼 스레드로 처리되었다. application.yml 설정을 확인하라.");
        return result;
    }

    /** {@code VirtualThread[#26]/runnable@ForkJoinPool-1-worker-1} → {@code ForkJoinPool-1-worker-1} */
    private static String carrierOf(Thread thread) {
        String s = thread.toString();
        int at = s.lastIndexOf('@');
        return at >= 0 ? s.substring(at + 1) : thread.getName();
    }
}
