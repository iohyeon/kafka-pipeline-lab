package com.pipeline.comparison;

import com.pipeline.outbox.OutboxEvent;
import com.pipeline.outbox.OutboxEventRepository;
import com.pipeline.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * BEFORE_COMMIT vs AFTER_COMMIT vs 직접 호출 — 3가지 방식 비교 서비스
 *
 * 같은 비즈니스(주문 생성 시뮬레이션)를 3가지 방식으로 구현하여
 * 각 방식의 TX 경계, 원자성, 동작 차이를 실측한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionPhaseComparisonService {

    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    // ──────────────────────────────────────────────
    // 방식 A: BEFORE_COMMIT
    // ──────────────────────────────────────────────
    // Facade가 도메인 이벤트만 발행 → BEFORE_COMMIT 리스너가 같은 TX에서 Outbox 저장
    // Facade는 Outbox를 모름
    @Transactional
    public String methodA_BeforeCommit(Long orderId) {
        log.info("[방식A] 비즈니스 로직 시작 — orderId={}", orderId);

        // 비즈니스 로직 (주문 생성 시뮬레이션)
        simulateBusinessLogic(orderId);

        // 도메인 이벤트 발행 — Facade는 Outbox를 모른다
        eventPublisher.publishEvent(new ComparisonEvent(
                orderId, "OrderCreatedEvent", "BEFORE_COMMIT",
                UUID.randomUUID().toString(), LocalDateTime.now()));

        log.info("[방식A] 이벤트 발행 완료 — TX 아직 커밋 안 됨");
        return "BEFORE_COMMIT";
    }

    // ──────────────────────────────────────────────
    // 방식 B: AFTER_COMMIT
    // ──────────────────────────────────────────────
    // Facade가 도메인 이벤트 발행 → AFTER_COMMIT 리스너가 TX 커밋 후 Kafka 즉시 발행
    // Outbox는 Facade에서 직접 저장 (같은 TX)
    @Transactional
    public String methodB_AfterCommit(Long orderId) {
        log.info("[방식B] 비즈니스 로직 시작 — orderId={}", orderId);

        // 비즈니스 로직
        simulateBusinessLogic(orderId);

        // Outbox 직접 저장 — 같은 TX (원자성)
        String eventId = UUID.randomUUID().toString();
        OutboxEvent outbox = OutboxEvent.create(
                "order.created.event-v1", String.valueOf(orderId),
                "OrderCreatedEvent",
                "{\"orderId\":" + orderId + ",\"method\":\"AFTER_COMMIT\",\"eventId\":\"" + eventId + "\"}");
        outboxEventRepository.save(outbox);

        // AFTER_COMMIT 이벤트 발행 — TX 커밋 후 Kafka 즉시 발행 시도
        eventPublisher.publishEvent(new ComparisonAfterCommitEvent(
                orderId, eventId, outbox.getId()));

        log.info("[방식B] Outbox 저장 + 이벤트 발행 완료 — TX 커밋 대기 중");
        return "AFTER_COMMIT";
    }

    // ──────────────────────────────────────────────
    // 방식 C: 직접 호출 (현재 loop-pack 프로젝트 방식)
    // ──────────────────────────────────────────────
    // Facade가 Outbox를 직접 저장. 이벤트 리스너 없음.
    // Relay 스케줄러가 나중에 Kafka로 발행.
    @Transactional
    public String methodC_DirectCall(Long orderId) {
        log.info("[방식C] 비즈니스 로직 시작 — orderId={}", orderId);

        // 비즈니스 로직
        simulateBusinessLogic(orderId);

        // Outbox 직접 저장 — 같은 TX (원자성)
        String eventId = UUID.randomUUID().toString();
        OutboxEvent outbox = OutboxEvent.create(
                "order.created.event-v1", String.valueOf(orderId),
                "OrderCreatedEvent",
                "{\"orderId\":" + orderId + ",\"method\":\"DIRECT_CALL\",\"eventId\":\"" + eventId + "\"}");
        outboxEventRepository.save(outbox);

        log.info("[방식C] Outbox 직접 저장 완료 — Relay가 나중에 Kafka 발행");
        return "DIRECT_CALL";
    }

    // ──────────────────────────────────────────────
    // 실패 시나리오 — TX 롤백 시 각 방식의 동작 확인
    // ──────────────────────────────────────────────
    @Transactional
    public String methodA_BeforeCommit_WithFailure(Long orderId) {
        log.info("[방식A-실패] 비즈니스 로직 시작 — orderId={}", orderId);
        simulateBusinessLogic(orderId);

        eventPublisher.publishEvent(new ComparisonEvent(
                orderId, "OrderCreatedEvent", "BEFORE_COMMIT_FAIL",
                UUID.randomUUID().toString(), LocalDateTime.now()));

        // 비즈니스 로직 실패 시뮬레이션 → TX 롤백
        throw new RuntimeException("비즈니스 로직 실패 시뮬레이션");
    }

    @Transactional
    public String methodC_DirectCall_WithFailure(Long orderId) {
        log.info("[방식C-실패] 비즈니스 로직 시작 — orderId={}", orderId);
        simulateBusinessLogic(orderId);

        OutboxEvent outbox = OutboxEvent.create(
                "order.created.event-v1", String.valueOf(orderId),
                "OrderCreatedEvent", "{\"orderId\":" + orderId + ",\"method\":\"DIRECT_FAIL\"}");
        outboxEventRepository.save(outbox);

        throw new RuntimeException("비즈니스 로직 실패 시뮬레이션");
    }

    private void simulateBusinessLogic(Long orderId) {
        // 주문 생성 시뮬레이션 (실제로는 DB INSERT)
        log.info("[Business] 주문 생성 처리 중 — orderId={}", orderId);
    }
}
