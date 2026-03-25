package com.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Consumer — 멱등성 적용 (Phase 5)
 *
 * 흐름:
 * ┌──────────────────────────────────────────────────────────────┐
 * │  메시지 수신                                                  │
 * │       │                                                       │
 * │       ▼                                                       │
 * │  eventId로 event_handled 조회                                 │
 * │       │                                                       │
 * │       ├── 이미 존재 → "중복 메시지" → ACK만 하고 스킵         │
 * │       │                                                       │
 * │       └── 존재 안 함 → @Transactional 시작                    │
 * │              │                                                │
 * │              ├── ① 비즈니스 로직 실행                         │
 * │              ├── ② event_handled INSERT (같은 TX)             │
 * │              └── ③ TX 커밋                                    │
 * │              │                                                │
 * │              ▼                                                │
 * │         ACK (offset 커밋)                                     │
 * └──────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;

    @KafkaListener(
            topics = "order.created.event-v1",
            groupId = "order-inventory-group",
            containerFactory = "manualAckFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);

            log.info("[Consumer] 메시지 수신 — partition={}, offset={}, key={}, eventId={}",
                    record.partition(), record.offset(), record.key(), event.getEventId());

            // ① 멱등성 체크 — 이미 처리한 이벤트인가?
            if (idempotencyService.isAlreadyHandled(event.getEventId())) {
                log.warn("[Consumer] 중복 메시지 스킵 — eventId={}, orderId={}",
                        event.getEventId(), event.getOrderId());
                ack.acknowledge();  // 중복이어도 ACK → offset 전진
                return;
            }

            // ② 비즈니스 로직 + 멱등 기록 (같은 TX)
            processOrderWithIdempotency(event);

            // ③ ACK
            ack.acknowledge();
            log.info("[Consumer] ACK 완료 — eventId={}, orderId={}", event.getEventId(), event.getOrderId());

        } catch (Exception e) {
            log.error("[Consumer] 처리 실패 — partition={}, offset={}, error={}",
                    record.partition(), record.offset(), e.getMessage(), e);
            // Phase 6에서 DLQ 전송으로 확장
        }
    }

    @Transactional
    protected void processOrderWithIdempotency(OrderCreatedEvent event) {
        // 비즈니스 로직
        log.info("[Consumer] 주문 처리 중 — orderId={}, amount={}", event.getOrderId(), event.getTotalAmount());

        // 멱등 기록 — 비즈니스와 같은 TX
        boolean recorded = idempotencyService.markHandled(event.getEventId(), "ORDER_CREATED");

        if (!recorded) {
            // UNIQUE 제약 조건에 의해 다른 Consumer가 이미 처리 중
            log.warn("[Consumer] 동시 처리 감지 — eventId={}", event.getEventId());
        }
    }
}
