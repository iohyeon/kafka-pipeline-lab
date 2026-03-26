package com.pipeline.comparison;

import com.pipeline.outbox.OutboxEvent;
import com.pipeline.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BEFORE_COMMIT vs AFTER_COMMIT 리스너 비교
 *
 * 같은 이벤트를 다른 TransactionPhase로 처리하여
 * TX 경계의 차이를 실측한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonEventListener {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    // ──────────────────────────────────────────────
    // BEFORE_COMMIT 리스너
    // ──────────────────────────────────────────────
    // TX가 커밋되기 직전에 실행 → 같은 TX 안
    // → Outbox INSERT가 비즈니스와 원자적
    // → 리스너 실패 시 TX 전체 롤백
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleBeforeCommit(ComparisonEvent event) {
        log.info("[BEFORE_COMMIT] 리스너 실행 — orderId={}, TX 아직 커밋 안 됨", event.orderId());

        // Outbox 저장 — 같은 TX 안에서 INSERT
        OutboxEvent outbox = OutboxEvent.create(
                "order.created.event-v1", String.valueOf(event.orderId()),
                event.eventType(),
                "{\"orderId\":" + event.orderId() + ",\"method\":\"" + event.method() + "\",\"eventId\":\"" + event.eventId() + "\"}");
        outboxEventRepository.save(outbox);

        log.info("[BEFORE_COMMIT] Outbox 저장 완료 — 같은 TX에서 INSERT, outboxId={}", outbox.getId());

        // 이 시점에서 TX는 아직 커밋되지 않았다.
        // 이 리스너가 예외를 던지면 비즈니스 TX 전체가 롤백된다.
    }

    // ──────────────────────────────────────────────
    // AFTER_COMMIT 리스너
    // ──────────────────────────────────────────────
    // TX가 커밋된 후에 실행 → TX 밖
    // → Outbox는 이미 TX 안에서 저장됨 (Facade에서 직접)
    // → 여기서는 Kafka 즉시 발행만 시도
    // → 실패해도 비즈니스 TX에 영향 없음
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAfterCommit(ComparisonAfterCommitEvent event) {
        log.info("[AFTER_COMMIT] 리스너 실행 — orderId={}, TX 이미 커밋됨", event.orderId());

        try {
            // Kafka 즉시 발행 시도 (Outbox와 별개로)
            acksAllKafkaTemplate.send("order.created.event-v1",
                    String.valueOf(event.orderId()),
                    "{\"orderId\":" + event.orderId() + ",\"method\":\"AFTER_COMMIT\",\"eventId\":\"" + event.eventId() + "}");

            log.info("[AFTER_COMMIT] Kafka 즉시 발행 성공 — orderId={}", event.orderId());

            // 발행 성공 → Outbox를 PUBLISHED로 마킹 (별도 TX)
            outboxEventRepository.findById(event.outboxId()).ifPresent(outbox -> {
                outbox.markPublished();
                outboxEventRepository.save(outbox);
            });

        } catch (Exception e) {
            // 실패해도 Outbox에 PENDING으로 남아있으므로 Relay가 재시도
            log.warn("[AFTER_COMMIT] Kafka 즉시 발행 실패 — Relay가 재시도 예정. error={}", e.getMessage());
        }
    }
}
