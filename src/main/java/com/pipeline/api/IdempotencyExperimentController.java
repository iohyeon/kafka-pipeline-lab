package com.pipeline.api;

import com.pipeline.event.OrderCreatedEvent;
import com.pipeline.idempotency.EventHandledRepository;
import com.pipeline.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 — 멱등성 실험 API
 */
@RestController
@RequestMapping("/api/experiment/idempotency")
@RequiredArgsConstructor
public class IdempotencyExperimentController {

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;
    private final IdempotencyService idempotencyService;
    private final EventHandledRepository eventHandledRepository;

    /**
     * 같은 eventId로 메시지를 2번 발행 → Consumer가 중복을 감지하는지 확인
     * POST /api/experiment/idempotency/duplicate-test/{orderId}
     */
    @PostMapping("/duplicate-test/{orderId}")
    public Map<String, Object> duplicateTest(@PathVariable Long orderId) {
        String fixedEventId = "duplicate-test-" + orderId;

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(orderId)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(29900))
                .eventId(fixedEventId)
                .occurredAt(LocalDateTime.now())
                .build();

        // 같은 eventId로 2번 발행
        acksAllKafkaTemplate.send("order.created.event-v1", String.valueOf(orderId), event);
        acksAllKafkaTemplate.send("order.created.event-v1", String.valueOf(orderId), event);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("eventId", fixedEventId);
        result.put("발행 횟수", 2);
        result.put("예상 결과", "Consumer 로그에서 첫 번째는 처리, 두 번째는 '중복 메시지 스킵'");
        return result;
    }

    /**
     * N건의 메시지를 같은 eventId로 발행 → 중복 처리 테스트
     * POST /api/experiment/idempotency/flood/{orderId}?count=10
     */
    @PostMapping("/flood/{orderId}")
    public Map<String, Object> floodTest(@PathVariable Long orderId,
                                          @RequestParam(defaultValue = "10") int count) {
        String fixedEventId = "flood-test-" + orderId;

        for (int i = 0; i < count; i++) {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId(orderId)
                    .userId(1L)
                    .totalAmount(BigDecimal.valueOf(29900))
                    .eventId(fixedEventId)
                    .occurredAt(LocalDateTime.now())
                    .build();
            acksAllKafkaTemplate.send("order.created.event-v1", String.valueOf(orderId), event);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("eventId", fixedEventId);
        result.put("발행 횟수", count);
        result.put("예상 결과", "1번만 처리, 나머지 " + (count - 1) + "번은 중복 스킵");
        return result;
    }

    /**
     * event_handled 테이블 상태 조회
     * GET /api/experiment/idempotency/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event_handled 총 건수", eventHandledRepository.count());
        result.put("최근 5건", eventHandledRepository.findAll().stream()
                .sorted((a, b) -> b.getHandledAt().compareTo(a.getHandledAt()))
                .limit(5)
                .map(e -> Map.of("eventId", e.getEventId(), "type", e.getEventType(), "handledAt", e.getHandledAt().toString()))
                .toList());
        return result;
    }
}
