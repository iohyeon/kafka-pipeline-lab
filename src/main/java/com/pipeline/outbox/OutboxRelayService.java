package com.pipeline.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox Relay — Polling 방식
 *
 * 스케줄러가 주기적으로 Outbox 테이블을 조회하여
 * PENDING 상태의 이벤트를 Kafka로 발행한다.
 *
 * 흐름:
 * ┌──────────┐     Polling (5초 간격)     ┌────────────┐
 * │  Outbox   │ ──────────────────────→   │   Relay    │
 * │  테이블    │  SELECT WHERE PENDING     │  Service   │
 * └──────────┘                            └─────┬──────┘
 *                                               │
 *                                          KafkaTemplate.send()
 *                                               │
 *                                               ▼
 *                                        ┌────────────┐
 *                                        │   Kafka    │
 *                                        └────────────┘
 *                                               │
 *                                          성공 → PUBLISHED
 *                                          실패 → FAILED + retryCount++
 *
 * 주의:
 * - 릴레이는 비즈니스 TX와 별개의 TX에서 동작
 * - at-least-once 보장 (Kafka 발행 성공 후 DB 업데이트 전에 죽으면 재발행)
 * - Consumer 쪽 멱등성이 반드시 필요 (Phase 5)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRY = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    /**
     * PENDING 이벤트 발행 — 5초마다 실행
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(BATCH_SIZE);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("[Relay] PENDING 이벤트 {}건 발행 시작", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            publishToKafka(event);
        }
    }

    /**
     * FAILED 이벤트 재시도 — 30초마다 실행
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void retryFailedEvents() {
        List<OutboxEvent> retryableEvents = outboxEventRepository.findRetryableEvents(MAX_RETRY, BATCH_SIZE);

        if (retryableEvents.isEmpty()) {
            return;
        }

        log.info("[Relay] FAILED 이벤트 {}건 재시도", retryableEvents.size());

        for (OutboxEvent event : retryableEvents) {
            event.markRetry();
            publishToKafka(event);
        }
    }

    /**
     * 오래된 PUBLISHED 이벤트 정리 — 1시간마다 실행
     */
    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanupPublishedEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        List<OutboxEvent> oldEvents = outboxEventRepository.findPublishedBefore(cutoff);

        if (!oldEvents.isEmpty()) {
            outboxEventRepository.deleteAll(oldEvents);
            log.info("[Relay] PUBLISHED 이벤트 {}건 정리 (7일 이전)", oldEvents.size());
        }
    }

    // ──────────────────────────────────────────────

    /**
     * 동기 방식으로 Kafka 발행.
     *
     * 비동기(whenComplete)로 하면 @Transactional이 이미 커밋된 후에
     * 콜백이 실행되어 markPublished()가 DB에 반영되지 않는 문제가 있다.
     * → send().get()으로 동기 대기 → 결과 확인 후 상태 변경 → TX 커밋 시 반영
     */
    private void publishToKafka(OutboxEvent event) {
        try {
            SendResult<String, Object> result = acksAllKafkaTemplate
                    .send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                    .get(10, TimeUnit.SECONDS);  // 동기 대기 (최대 10초)

            var metadata = result.getRecordMetadata();
            log.info("[Relay] 발행 성공 — outboxId={}, topic={}, partition={}, offset={}",
                    event.getId(), event.getTopic(), metadata.partition(), metadata.offset());
            event.markPublished();

        } catch (ExecutionException e) {
            log.error("[Relay] 발행 실패 — outboxId={}, error={}", event.getId(), e.getCause().getMessage());
            event.markFailed(e.getCause().getMessage());
        } catch (TimeoutException e) {
            log.error("[Relay] 발행 타임아웃 — outboxId={}", event.getId());
            event.markFailed("Kafka send timeout (10s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Relay] 발행 중단 — outboxId={}", event.getId());
            event.markFailed("Interrupted");
        }
    }
}
