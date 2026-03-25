package com.pipeline.api;

import com.pipeline.order.OrderService;
import com.pipeline.outbox.OutboxEventRepository;
import com.pipeline.outbox.OutboxRelayService;
import com.pipeline.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 4 — Outbox 패턴 실험 API
 */
@RestController
@RequestMapping("/api/experiment/outbox")
@RequiredArgsConstructor
public class OutboxExperimentController {

    private final OrderService orderService;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRelayService outboxRelayService;

    /**
     * Outbox 패턴으로 주문 생성
     * POST /api/experiment/outbox/order
     *
     * 1. DB에 주문 + Outbox 이벤트를 같은 TX로 저장
     * 2. Relay가 5초 후 Outbox를 읽어서 Kafka로 발행
     */
    @PostMapping("/order")
    public Map<String, Object> createOrder(
            @RequestParam(defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "29900") BigDecimal amount) {

        Long orderId = orderService.createOrder(userId, amount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("outbox", "PENDING — 5초 내에 Relay가 Kafka로 발행 예정");
        return result;
    }

    /**
     * 대량 주문 생성
     * POST /api/experiment/outbox/orders/bulk/{count}
     */
    @PostMapping("/orders/bulk/{count}")
    public Map<String, Object> createBulkOrders(@PathVariable int count) {
        for (int i = 0; i < count; i++) {
            orderService.createOrder((long) (i % 10), BigDecimal.valueOf(10000 + i * 100));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", count);
        result.put("outbox", count + "건 PENDING — Relay가 순차 발행 예정");
        return result;
    }

    /**
     * Outbox 상태 조회
     * GET /api/experiment/outbox/status
     */
    @GetMapping("/status")
    public Map<String, Object> getOutboxStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("PENDING", outboxEventRepository.countByStatus(OutboxStatus.PENDING));
        result.put("PUBLISHED", outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED));
        result.put("FAILED", outboxEventRepository.countByStatus(OutboxStatus.FAILED));
        result.put("total", outboxEventRepository.count());
        return result;
    }

    /**
     * Relay 수동 실행 (스케줄러 기다리지 않고 즉시 발행)
     * POST /api/experiment/outbox/relay/trigger
     */
    @PostMapping("/relay/trigger")
    public Map<String, String> triggerRelay() {
        outboxRelayService.relayPendingEvents();
        return Map.of("result", "Relay 수동 실행 완료");
    }

    /**
     * 실험 가이드
     * GET /api/experiment/outbox/guide
     */
    @GetMapping("/guide")
    public Map<String, String> getGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        guide.put("실험1: 기본 흐름",
                "1) POST /order → 2) GET /status (PENDING 확인) → 3) 5초 대기 → 4) GET /status (PUBLISHED 확인)");
        guide.put("실험2: Kafka 장애 시",
                "1) docker stop kafka-1 kafka-2 kafka-3 → 2) POST /order → 3) GET /status (PENDING 유지) → 4) docker start → 5) PUBLISHED로 변경");
        guide.put("실험3: 대량",
                "1) POST /orders/bulk/100 → 2) GET /status로 PENDING→PUBLISHED 전환 관찰");
        guide.put("Outbox DB 직접 확인",
                "docker exec -it pipeline-mysql mysql -upipeline -ppipeline pipeline -e 'SELECT id,topic,partition_key,status,retry_count,created_at FROM outbox_event ORDER BY id DESC LIMIT 10'");
        return guide;
    }
}
