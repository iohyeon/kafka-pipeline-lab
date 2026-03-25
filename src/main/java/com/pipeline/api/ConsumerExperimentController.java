package com.pipeline.api;

import com.pipeline.consumer.SlowConsumer;
import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 — Consumer 심화 실험 API
 */
@RestController
@RequestMapping("/api/experiment/consumer")
@RequiredArgsConstructor
public class ConsumerExperimentController {

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;
    private final SlowConsumer slowConsumer;

    /**
     * SlowConsumer의 처리 지연 시간을 동적으로 변경
     * POST /api/experiment/consumer/slow/delay/{delayMs}
     *
     * 사용법:
     * 1. delay를 0으로 → 정상 속도
     * 2. delay를 5000으로 → 5초 지연 → LAG 쌓임 관찰
     * 3. delay를 130000으로 → max.poll.interval.ms(120s) 초과 → 리밸런싱 발생
     */
    @PostMapping("/slow/delay/{delayMs}")
    public Map<String, Object> setSlowDelay(@PathVariable long delayMs) {
        slowConsumer.setProcessingDelayMs(delayMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processingDelayMs", delayMs);
        result.put("상태", delayMs == 0 ? "정상 속도" :
                   delayMs < 120000 ? "느린 처리 — LAG 쌓임 관찰 가능" :
                   "max.poll.interval.ms 초과 — 리밸런싱 발생 예상");
        return result;
    }

    /**
     * inventory 토픽에 메시지 발행 (SlowConsumer 실험용)
     * POST /api/experiment/consumer/inventory/publish/{count}
     */
    @PostMapping("/inventory/publish/{count}")
    public String publishInventoryEvents(@PathVariable int count) {
        for (int i = 1; i <= count; i++) {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId((long) i)
                    .userId(1L)
                    .totalAmount(BigDecimal.valueOf(10000))
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(LocalDateTime.now())
                    .build();

            String key = String.valueOf(i % 10);  // productId 시뮬레이션
            acksAllKafkaTemplate.send("inventory.deduct.event-v1", key, event);
        }
        return "Published " + count + " inventory events";
    }

    /**
     * 현재 SlowConsumer 상태 조회
     * GET /api/experiment/consumer/slow/status
     */
    @GetMapping("/slow/status")
    public Map<String, Object> getSlowStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processingDelayMs", slowConsumer.getProcessingDelayMs());
        return result;
    }

    /**
     * Consumer Group 상태 확인 가이드
     * GET /api/experiment/consumer/guide
     */
    @GetMapping("/guide")
    public Map<String, String> getExperimentGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        guide.put("실험1: LAG 관찰",
                "1) POST /slow/delay/5000  →  2) POST /inventory/publish/50  →  3) kafka-consumer-groups.sh --describe");
        guide.put("실험2: 리밸런싱",
                "1) POST /slow/delay/130000  →  2) POST /inventory/publish/10  →  3) 2분 후 로그에 리밸런싱 발생");
        guide.put("실험3: Consumer 장애",
                "1) 앱을 2개 인스턴스로 실행  →  2) 하나 kill  →  3) 나머지가 파티션 이어받는지 확인");
        guide.put("Consumer Group 확인 명령어",
                "docker exec kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group inventory-slow-group");
        return guide;
    }
}
