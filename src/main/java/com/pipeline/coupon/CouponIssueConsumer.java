package com.pipeline.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.dlq.DlqPublisher;
import com.pipeline.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 선착순 쿠폰 발급 Consumer
 *
 * Phase 1~6 전부 통합:
 * - key=couponId → 같은 쿠폰은 같은 파티션 → 순차 처리 (Phase 2)
 * - Manual ACK (Phase 3)
 * - 멱등성 체크 (Phase 5)
 * - 3회 재시도 + 지수 백오프 + DLQ (Phase 6)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueConsumer {

    private static final int MAX_RETRY = 3;

    private final ObjectMapper objectMapper;
    private final CouponIssueService couponIssueService;
    private final IdempotencyService idempotencyService;
    private final DlqPublisher dlqPublisher;

    @KafkaListener(
            topics = "coupon.issue.request-v1",
            groupId = "coupon-issue-group",
            containerFactory = "manualAckFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            CouponEvent event = objectMapper.readValue(record.value(), CouponEvent.class);

            log.info("[CouponConsumer] 수신 — partition={}, offset={}, couponId={}, userId={}, eventId={}",
                    record.partition(), record.offset(), event.getCouponId(), event.getUserId(), event.getEventId());

            // 멱등성 체크
            if (idempotencyService.isAlreadyHandled(event.getEventId())) {
                log.warn("[CouponConsumer] 중복 스킵 — eventId={}", event.getEventId());
                ack.acknowledge();
                return;
            }

            // 재시도 루프
            boolean success = false;
            Exception lastException = null;

            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try {
                    CouponIssueService.CouponIssueResult result = couponIssueService.issue(event);

                    switch (result) {
                        case SUCCESS -> log.info("[CouponConsumer] 발급 성공 — couponId={}, userId={}",
                                event.getCouponId(), event.getUserId());
                        case OUT_OF_STOCK -> log.warn("[CouponConsumer] 재고 소진 — couponId={}, userId={}",
                                event.getCouponId(), event.getUserId());
                        case ALREADY_ISSUED -> log.warn("[CouponConsumer] 이미 발급됨 — couponId={}, userId={}",
                                event.getCouponId(), event.getUserId());
                    }

                    success = true;
                    break;

                } catch (Exception e) {
                    lastException = e;
                    log.warn("[CouponConsumer] 재시도 {}/{} 실패 — eventId={}, error={}",
                            attempt, MAX_RETRY, event.getEventId(), e.getMessage());

                    if (attempt < MAX_RETRY) {
                        long backoffMs = (long) Math.pow(2, attempt - 1) * 1000;
                        Thread.sleep(backoffMs);
                    }
                }
            }

            if (success) {
                ack.acknowledge();
            } else {
                log.error("[CouponConsumer] {}회 실패 → DLQ — eventId={}", MAX_RETRY, event.getEventId());
                dlqPublisher.sendToDlq(record, lastException, MAX_RETRY);
                ack.acknowledge();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[CouponConsumer] 예외 — partition={}, offset={}, error={}",
                    record.partition(), record.offset(), e.getMessage(), e);
            dlqPublisher.sendToDlq(record, e, 0);
            ack.acknowledge();
        }
    }
}
