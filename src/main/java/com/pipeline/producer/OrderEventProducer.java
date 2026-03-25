package com.pipeline.producer;

import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Producer — Phase 2 심화에서 확장 예정
 *
 * 핵심 포인트:
 * 1. key = orderId → 같은 주문의 이벤트는 같은 파티션으로 라우팅
 * 2. KafkaTemplate.send()는 비동기 — CompletableFuture 반환
 * 3. callback으로 성공/실패 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final String TOPIC = "order.created.event-v1";

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    public void publish(OrderCreatedEvent event) {
        String key = String.valueOf(event.getOrderId());

        CompletableFuture<SendResult<String, Object>> future =
                acksAllKafkaTemplate.send(TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[Producer] 메시지 발행 실패 — orderId={}, error={}",
                        event.getOrderId(), ex.getMessage(), ex);
            } else {
                var metadata = result.getRecordMetadata();
                log.info("[Producer] 메시지 발행 성공 — topic={}, partition={}, offset={}, key={}",
                        metadata.topic(), metadata.partition(), metadata.offset(), key);
            }
        });
    }
}
