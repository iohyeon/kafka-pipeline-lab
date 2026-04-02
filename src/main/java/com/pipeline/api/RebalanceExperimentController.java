package com.pipeline.api;

import com.pipeline.event.OrderCreatedEvent;
import com.pipeline.rebalance.RebalanceEventLog;
import com.pipeline.rebalance.RebalanceEventLog.RebalanceEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Eager vs Cooperative 리밸런싱 비교 실험 API
 *
 * 실험 흐름:
 * 1. POST /start/eager + /start/cooperative → 양쪽 그룹 시작
 * 2. POST /publish/100 → 메시지 발행
 * 3. DELETE /log → 초기 리밸런싱 로그 제거 (안정화 후)
 * 4. POST /stop/eager-1 → Eager 그룹에서 컨슈머 1개 제거
 * 5. POST /stop/coop-1 → Cooperative 그룹에서 컨슈머 1개 제거
 * 6. GET /log → 두 프로토콜의 리밸런싱 이벤트 비교
 */
@RestController
@RequestMapping("/api/experiment/rebalance")
@RequiredArgsConstructor
public class RebalanceExperimentController {

    private final KafkaListenerEndpointRegistry registry;
    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;
    private final RebalanceEventLog eventLog;

    private static final String TOPIC = "rebalance.experiment-v1";
    private static final List<String> EAGER_IDS = List.of("eager-0", "eager-1", "eager-2");
    private static final List<String> COOP_IDS = List.of("coop-0", "coop-1", "coop-2");
    private static final List<String> STATIC_IDS = List.of("static-0", "static-1", "static-2");

    // ──────────────────────────────────────────────
    // 컨슈머 시작/중지
    // ──────────────────────────────────────────────

    /**
     * 특정 프로토콜의 컨슈머 3개 전부 시작
     * POST /api/experiment/rebalance/start/{protocol}
     * protocol: "eager" 또는 "cooperative"
     */
    @PostMapping("/start/{protocol}")
    public Map<String, Object> startConsumers(@PathVariable String protocol) {
        List<String> ids = switch (protocol) {
            case "eager" -> EAGER_IDS;
            case "static" -> STATIC_IDS;
            default -> COOP_IDS;
        };
        List<String> started = new ArrayList<>();

        for (String id : ids) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            if (container != null && !container.isRunning()) {
                container.start();
                started.add(id);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", protocol);
        result.put("started", started);
        result.put("assignmentStrategy", protocol.equals("eager") ? "RangeAssignor" : "CooperativeStickyAssignor");
        if (protocol.equals("static")) {
            result.put("staticMembership", true);
            result.put("sessionTimeoutMs", 45000);
            result.put("설명", "group.instance.id 설정됨. 컨슈머가 떠나도 45초 동안 리밸런싱 없이 대기.");
        }
        return result;
    }

    /**
     * 특정 컨슈머 1개 중지 → 리밸런싱 트리거
     * POST /api/experiment/rebalance/stop/{consumerId}
     * consumerId: "eager-1", "coop-1" 등
     */
    @PostMapping("/stop/{consumerId}")
    public Map<String, Object> stopConsumer(@PathVariable String consumerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        MessageListenerContainer container = registry.getListenerContainer(consumerId);

        if (container == null) {
            result.put("error", consumerId + " 컨테이너를 찾을 수 없습니다");
            return result;
        }
        if (!container.isRunning()) {
            result.put("error", consumerId + " 이미 중지된 상태입니다");
            return result;
        }

        container.stop();
        result.put("stopped", consumerId);
        result.put("설명", consumerId + " 중지 → 나머지 컨슈머에서 리밸런싱 발생. 10초 후 GET /log로 확인하세요.");
        return result;
    }

    /**
     * 특정 컨슈머 1개 재시작
     * POST /api/experiment/rebalance/restart/{consumerId}
     */
    @PostMapping("/restart/{consumerId}")
    public Map<String, Object> restartConsumer(@PathVariable String consumerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        MessageListenerContainer container = registry.getListenerContainer(consumerId);

        if (container == null) {
            result.put("error", consumerId + " 컨테이너를 찾을 수 없습니다");
            return result;
        }

        if (!container.isRunning()) {
            container.start();
            result.put("restarted", consumerId);
        } else {
            result.put("info", consumerId + " 이미 실행 중입니다");
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // 메시지 발행
    // ──────────────────────────────────────────────

    /**
     * 실험용 메시지 발행
     * POST /api/experiment/rebalance/publish/{count}
     */
    @PostMapping("/publish/{count}")
    public Map<String, Object> publishMessages(@PathVariable int count) {
        for (int i = 1; i <= count; i++) {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId((long) i)
                    .userId(1L)
                    .totalAmount(BigDecimal.valueOf(10000))
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(LocalDateTime.now())
                    .build();

            // key를 3개 값으로 순환 → 3개 파티션에 고르게 분배
            String key = "key-" + (i % 3);
            acksAllKafkaTemplate.send(TOPIC, key, event);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", count);
        result.put("topic", TOPIC);
        return result;
    }

    // ──────────────────────────────────────────────
    // 리밸런싱 로그 조회
    // ──────────────────────────────────────────────

    /**
     * 리밸런싱 이벤트 로그 조회 — 두 프로토콜 비교
     * GET /api/experiment/rebalance/log
     */
    @GetMapping("/log")
    public Map<String, Object> getLog() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("EAGER", buildProtocolSummary(eventLog.getByProtocol("EAGER")));
        result.put("COOPERATIVE", buildProtocolSummary(eventLog.getByProtocol("COOPERATIVE")));
        result.put("STATIC", buildProtocolSummary(eventLog.getByProtocol("STATIC")));
        return result;
    }

    /**
     * 로그 초기화
     * DELETE /api/experiment/rebalance/log
     */
    @DeleteMapping("/log")
    public Map<String, String> clearLog() {
        eventLog.clear();
        return Map.of("result", "로그 초기화 완료. 이제 컨슈머를 중지하고 리밸런싱을 관찰하세요.");
    }

    // ──────────────────────────────────────────────
    // 상태 조회
    // ──────────────────────────────────────────────

    /**
     * 전체 컨슈머 상태 조회
     * GET /api/experiment/rebalance/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eager_group", getGroupStatus(EAGER_IDS));
        result.put("cooperative_group", getGroupStatus(COOP_IDS));
        result.put("static_group", getGroupStatus(STATIC_IDS));
        return result;
    }

    /**
     * 실험 가이드
     * GET /api/experiment/rebalance/guide
     */
    @GetMapping("/guide")
    public Map<String, String> getGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        guide.put("Step 1", "POST /api/experiment/rebalance/start/eager — Eager 그룹 시작 (3 컨슈머)");
        guide.put("Step 2", "POST /api/experiment/rebalance/start/cooperative — Cooperative 그룹 시작 (3 컨슈머)");
        guide.put("Step 3", "POST /api/experiment/rebalance/publish/60 — 메시지 60개 발행");
        guide.put("Step 4", "5초 대기 후 DELETE /api/experiment/rebalance/log — 초기 리밸런싱 로그 제거");
        guide.put("Step 5", "POST /api/experiment/rebalance/stop/eager-1 — Eager 컨슈머 1개 중지 → 리밸런싱 트리거");
        guide.put("Step 6", "10초 대기");
        guide.put("Step 7", "POST /api/experiment/rebalance/stop/coop-1 — Cooperative 컨슈머 1개 중지 → 리밸런싱 트리거");
        guide.put("Step 8", "10초 대기");
        guide.put("Step 9", "GET /api/experiment/rebalance/log — 두 프로토콜의 리밸런싱 이벤트 비교");
        guide.put("관찰 포인트",
                "EAGER: eager-0, eager-2 모두 REVOKED에 자기 파티션 포함 (전원 중단). " +
                "COOPERATIVE: coop-0, coop-2의 REVOKED는 빈 리스트 (기존 파티션 처리 계속).");
        return guide;
    }

    // ──────────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────────

    private Map<String, Object> buildProtocolSummary(List<RebalanceEvent> events) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalEvents", events.size());
        summary.put("events", events);

        // REVOKED 이벤트 중 파티션이 비어있지 않은 것 카운트
        long revokedWithPartitions = events.stream()
                .filter(e -> "REVOKED".equals(e.eventType()) && !e.partitions().isEmpty())
                .count();
        summary.put("revokedWithPartitions", revokedWithPartitions);
        summary.put("해석", revokedWithPartitions > 0
                ? "파티션 반납 발생 — stop-the-world 구간 존재"
                : "파티션 반납 없음 — 기존 처리 중단 없이 리밸런싱 완료");
        return summary;
    }

    private List<Map<String, Object>> getGroupStatus(List<String> ids) {
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", id);
            MessageListenerContainer container = registry.getListenerContainer(id);
            status.put("running", container != null && container.isRunning());
            statuses.add(status);
        }
        return statuses;
    }
}
