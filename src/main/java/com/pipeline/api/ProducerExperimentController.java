package com.pipeline.api;

import com.pipeline.event.OrderCreatedEvent;
import com.pipeline.producer.AcksComparisonProducer;
import com.pipeline.producer.KeyRoutingProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 — Producer 심화 실험 API
 */
@RestController
@RequestMapping("/api/experiment/producer")
@RequiredArgsConstructor
public class ProducerExperimentController {

    private final AcksComparisonProducer acksComparisonProducer;
    private final KeyRoutingProducer keyRoutingProducer;

    /**
     * acks 설정별 비교 실험
     * POST /api/experiment/producer/acks-compare/{orderId}
     *
     * 같은 메시지를 acks=0, 1, all로 각각 발행하고 응답 시간을 비교한다.
     */
    @PostMapping("/acks-compare/{orderId}")
    public Map<String, Object> compareAcks(@PathVariable Long orderId) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(orderId)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(29900))
                .eventId(UUID.randomUUID().toString())
                .occurredAt(LocalDateTime.now())
                .build();

        Map<String, Object> result = new LinkedHashMap<>();

        long acks0Time = acksComparisonProducer.publishWithAcksZero(event);
        result.put("acks=0 (fire-and-forget)", acks0Time + "ms");

        long acks1Time = acksComparisonProducer.publishWithAcksOne(event);
        result.put("acks=1 (leader-only)", acks1Time + "ms");

        long acksAllTime = acksComparisonProducer.publishWithAcksAll(event);
        result.put("acks=all (all-ISR)", acksAllTime + "ms");

        result.put("설명", Map.of(
                "acks=0", "브로커 응답 안 기다림. 유실 가능. 로그/클릭 이벤트용.",
                "acks=1", "Leader만 확인. Leader 장애 시 유실 가능.",
                "acks=all", "모든 ISR 확인. 가장 안전. 주문/결제용."
        ));

        return result;
    }

    /**
     * acks 설정별 대량 발행 비교
     * POST /api/experiment/producer/acks-compare/bulk/{count}
     */
    @PostMapping("/acks-compare/bulk/{count}")
    public Map<String, Object> compareAcksBulk(@PathVariable int count) {
        Map<String, Object> result = new LinkedHashMap<>();

        // acks=0 벌크
        long start0 = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            OrderCreatedEvent event = buildEvent((long) i);
            acksComparisonProducer.publishWithAcksZero(event);
        }
        result.put("acks=0 total", (System.currentTimeMillis() - start0) + "ms for " + count + " messages");

        // acks=1 벌크
        long start1 = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            OrderCreatedEvent event = buildEvent((long) i);
            acksComparisonProducer.publishWithAcksOne(event);
        }
        result.put("acks=1 total", (System.currentTimeMillis() - start1) + "ms for " + count + " messages");

        // acks=all 벌크
        long startAll = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            OrderCreatedEvent event = buildEvent((long) i);
            acksComparisonProducer.publishWithAcksAll(event);
        }
        result.put("acks=all total", (System.currentTimeMillis() - startAll) + "ms for " + count + " messages");

        return result;
    }

    /**
     * Key 라우팅 검증
     * POST /api/experiment/producer/key-routing?repeatPerKey=3
     *
     * orderId 1~6으로 각각 repeatPerKey번 발행.
     * 같은 orderId는 항상 같은 파티션에 가는지 확인.
     */
    @PostMapping("/key-routing")
    public Map<String, String> verifyKeyRouting(
            @RequestParam(defaultValue = "3") int repeatPerKey) {
        Long[] orderIds = {1L, 2L, 3L, 4L, 5L, 6L};
        return keyRoutingProducer.verifyKeyRouting(orderIds, repeatPerKey);
    }

    private OrderCreatedEvent buildEvent(Long orderId) {
        return OrderCreatedEvent.builder()
                .orderId(orderId)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(10000))
                .eventId(UUID.randomUUID().toString())
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
