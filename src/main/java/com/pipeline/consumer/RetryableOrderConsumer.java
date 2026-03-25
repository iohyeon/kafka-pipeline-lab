package com.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.dlq.DlqPublisher;
import com.pipeline.event.OrderCreatedEvent;
import com.pipeline.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재시도 + DLQ Consumer — Phase 6
 *
 * 처리 흐름:
 * ┌────────────────────────────────────────────────────────────────────┐
 * │  메시지 수신                                                       │
 * │       │                                                            │
 * │       ▼                                                            │
 * │  멱등성 체크 (Phase 5)                                             │
 * │       │                                                            │
 * │       ├── 이미 처리됨 → 스킵 + ACK                                │
 * │       │                                                            │
 * │       └── 미처리 → 비즈니스 로직 실행                              │
 * │              │                                                     │
 * │              ├── 성공 → event_handled 기록 + ACK                   │
 * │              │                                                     │
 * │              └── 실패 → 재시도 (최대 3회)                          │
 * │                    │                                               │
 * │                    ├── 재시도 성공 → event_handled 기록 + ACK       │
 * │                    │                                               │
 * │                    └── 3회 모두 실패 → DLQ 전송 + ACK              │
 * │                                         (메시지를 DLQ에 격리)      │
 * └────────────────────────────────────────────────────────────────────┘
 *
 * 별도 토픽(inventory.deduct.event-v1)을 사용하여 기존 Consumer와 분리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryableOrderConsumer {

    private static final int MAX_RETRY = 3;

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final DlqPublisher dlqPublisher;

    // 의도적 실패 모드 (실험용)
    private volatile boolean failMode = false;

    @KafkaListener(
            topics = "inventory.deduct.event-v1",
            groupId = "inventory-retry-group",
            containerFactory = "manualAckFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);

            log.info("[RetryConsumer] 수신 — partition={}, offset={}, eventId={}",
                    record.partition(), record.offset(), event.getEventId());

            // 멱등성 체크
            if (idempotencyService.isAlreadyHandled(event.getEventId())) {
                log.warn("[RetryConsumer] 중복 스킵 — eventId={}", event.getEventId());
                ack.acknowledge();
                return;
            }

            // 재시도 루프
            boolean success = false;
            Exception lastException = null;

            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try {
                    processWithRetry(event, attempt);
                    success = true;
                    break;
                } catch (Exception e) {
                    lastException = e;
                    log.warn("[RetryConsumer] 재시도 {}/{} 실패 — eventId={}, error={}",
                            attempt, MAX_RETRY, event.getEventId(), e.getMessage());

                    if (attempt < MAX_RETRY) {
                        // 백오프: 1초 → 2초 → 4초 (지수 백오프)
                        long backoffMs = (long) Math.pow(2, attempt - 1) * 1000;
                        log.info("[RetryConsumer] {}ms 후 재시도...", backoffMs);
                        Thread.sleep(backoffMs);
                    }
                }
            }

            if (success) {
                ack.acknowledge();
            } else {
                // 3회 모두 실패 → DLQ 전송
                log.error("[RetryConsumer] {}회 재시도 모두 실패 → DLQ 전송 — eventId={}",
                        MAX_RETRY, event.getEventId());
                dlqPublisher.sendToDlq(record, lastException, MAX_RETRY);
                ack.acknowledge();  // DLQ에 보냈으므로 원본 토픽에서는 ACK
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[RetryConsumer] 중단됨 — partition={}, offset={}", record.partition(), record.offset());
        } catch (Exception e) {
            log.error("[RetryConsumer] 예외 — partition={}, offset={}, error={}",
                    record.partition(), record.offset(), e.getMessage(), e);
            dlqPublisher.sendToDlq(record, e, 0);
            ack.acknowledge();
        }
    }

    @Transactional
    protected void processWithRetry(OrderCreatedEvent event, int attempt) {
        // 의도적 실패 모드 (실험용)
        if (failMode) {
            throw new RuntimeException("의도적 실패 (failMode=true) — attempt=" + attempt);
        }

        log.info("[RetryConsumer] 처리 중 — orderId={}, attempt={}", event.getOrderId(), attempt);

        // 비즈니스 로직 + 멱등 기록 (같은 TX)
        idempotencyService.markHandled(event.getEventId(), "INVENTORY_DEDUCT");
    }

    public void setFailMode(boolean failMode) {
        this.failMode = failMode;
        log.info("[RetryConsumer] failMode 변경 — {}", failMode);
    }

    public boolean isFailMode() {
        return failMode;
    }
}
