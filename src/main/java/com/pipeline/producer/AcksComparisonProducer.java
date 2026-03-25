package com.pipeline.producer;

import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * acks 설정별 Producer 비교 실험용
 *
 * 같은 메시지를 3가지 acks 설정으로 보내서
 * 응답 시간, 성공/실패, 동작 차이를 비교한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcksComparisonProducer {

    private static final String TOPIC = "order.created.event-v1";

    private final KafkaTemplate<String, Object> acksZeroKafkaTemplate;
    private final KafkaTemplate<String, Object> acksOneKafkaTemplate;
    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    /**
     * acks=0으로 발행 — Fire and Forget
     */
    public long publishWithAcksZero(OrderCreatedEvent event) {
        String key = String.valueOf(event.getOrderId());
        long start = System.currentTimeMillis();

        CompletableFuture<SendResult<String, Object>> future =
                acksZeroKafkaTemplate.send(TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            long elapsed = System.currentTimeMillis() - start;
            if (ex != null) {
                log.error("[acks=0] 실패 — orderId={}, elapsed={}ms, error={}",
                        event.getOrderId(), elapsed, ex.getMessage());
            } else {
                var metadata = result.getRecordMetadata();
                log.info("[acks=0] 성공 — partition={}, offset={}, elapsed={}ms",
                        metadata.partition(), metadata.offset(), elapsed);
            }
        });

        return System.currentTimeMillis() - start;
    }

    /**
     * acks=1로 발행 — Leader만 확인
     */
    public long publishWithAcksOne(OrderCreatedEvent event) {
        String key = String.valueOf(event.getOrderId());
        long start = System.currentTimeMillis();

        CompletableFuture<SendResult<String, Object>> future =
                acksOneKafkaTemplate.send(TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            long elapsed = System.currentTimeMillis() - start;
            if (ex != null) {
                log.error("[acks=1] 실패 — orderId={}, elapsed={}ms, error={}",
                        event.getOrderId(), elapsed, ex.getMessage());
            } else {
                var metadata = result.getRecordMetadata();
                log.info("[acks=1] 성공 — partition={}, offset={}, elapsed={}ms",
                        metadata.partition(), metadata.offset(), elapsed);
            }
        });

        return System.currentTimeMillis() - start;
    }

    /**
     * acks=all로 발행 — 모든 ISR 확인 (가장 안전)
     */
    public long publishWithAcksAll(OrderCreatedEvent event) {
        String key = String.valueOf(event.getOrderId());
        long start = System.currentTimeMillis();

        CompletableFuture<SendResult<String, Object>> future =
                acksAllKafkaTemplate.send(TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            long elapsed = System.currentTimeMillis() - start;
            if (ex != null) {
                log.error("[acks=all] 실패 — orderId={}, elapsed={}ms, error={}",
                        event.getOrderId(), elapsed, ex.getMessage());
            } else {
                var metadata = result.getRecordMetadata();
                log.info("[acks=all] 성공 — partition={}, offset={}, elapsed={}ms",
                        metadata.partition(), metadata.offset(), elapsed);
            }
        });

        return System.currentTimeMillis() - start;
    }
}
