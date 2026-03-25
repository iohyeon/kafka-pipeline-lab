package com.pipeline.api;

import com.pipeline.consumer.RetryableOrderConsumer;
import com.pipeline.dlq.DlqConsumer;
import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6 — DLQ + 에러 핸들링 실험 API
 */
@RestController
@RequestMapping("/api/experiment/dlq")
@RequiredArgsConstructor
public class DlqExperimentController {

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;
    private final RetryableOrderConsumer retryableOrderConsumer;
    private final DlqConsumer dlqConsumer;

    /**
     * 의도적 실패 모드 ON/OFF
     * POST /api/experiment/dlq/fail-mode/{enabled}
     */
    @PostMapping("/fail-mode/{enabled}")
    public Map<String, Object> setFailMode(@PathVariable boolean enabled) {
        retryableOrderConsumer.setFailMode(enabled);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("failMode", enabled);
        result.put("설명", enabled
                ? "Consumer가 모든 메시지를 의도적으로 실패 → 3회 재시도 후 DLQ로 전송"
                : "정상 모드로 복귀");
        return result;
    }

    /**
     * inventory 토픽에 메시지 발행 (재시도 + DLQ 실험용)
     * POST /api/experiment/dlq/publish/{count}
     */
    @PostMapping("/publish/{count}")
    public Map<String, Object> publish(@PathVariable int count) {
        for (int i = 1; i <= count; i++) {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId((long) i)
                    .userId(1L)
                    .totalAmount(BigDecimal.valueOf(10000 + i * 100))
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(LocalDateTime.now())
                    .build();

            acksAllKafkaTemplate.send("inventory.deduct.event-v1", String.valueOf(i), event);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", count);
        result.put("failMode", retryableOrderConsumer.isFailMode());
        return result;
    }

    /**
     * DLQ 메시지 조회
     * GET /api/experiment/dlq/messages
     */
    @GetMapping("/messages")
    public Map<String, Object> getDlqMessages() {
        List<Map<String, String>> messages = dlqConsumer.getRecentDlqMessages();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dlqCount", messages.size());
        result.put("messages", messages);
        return result;
    }

    /**
     * 실험 가이드
     * GET /api/experiment/dlq/guide
     */
    @GetMapping("/guide")
    public Map<String, String> getGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        guide.put("실험1: 정상 처리",
                "1) POST /fail-mode/false → 2) POST /publish/5 → 정상 처리 확인");
        guide.put("실험2: 실패 → DLQ",
                "1) POST /fail-mode/true → 2) POST /publish/3 → 3) 로그에서 3회 재시도 확인 → 4) GET /messages에서 DLQ 메시지 확인");
        guide.put("실험3: 실패 후 복구",
                "1) POST /fail-mode/true → 2) POST /publish/3 → 3) POST /fail-mode/false → 이미 DLQ로 간 메시지는 복구 안 됨 확인");
        guide.put("DLQ 토픽 직접 확인",
                "docker exec kafka-1 kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic pipeline.dlq-v1 --from-beginning");
        return guide;
    }
}
