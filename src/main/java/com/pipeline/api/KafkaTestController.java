package com.pipeline.api;

import com.pipeline.event.OrderCreatedEvent;
import com.pipeline.producer.OrderEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Phase 1 검증용 API
 * - 메시지 발행 → Kafka UI에서 파티션 분배 확인
 * - 같은 orderId로 여러 번 호출 → 같은 파티션에 들어가는지 확인
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class KafkaTestController {

    private final OrderEventProducer orderEventProducer;

    /**
     * 단건 주문 이벤트 발행
     * POST /api/test/order/{orderId}
     */
    @PostMapping("/order/{orderId}")
    public String publishOrder(@PathVariable Long orderId) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(orderId)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(29900))
                .eventId(UUID.randomUUID().toString())
                .occurredAt(LocalDateTime.now())
                .build();

        orderEventProducer.publish(event);
        return "Published orderId=" + orderId;
    }

    /**
     * 다건 주문 이벤트 발행 — 파티션 분배 확인용
     * POST /api/test/orders/bulk/{count}
     */
    @PostMapping("/orders/bulk/{count}")
    public String publishBulk(@PathVariable int count) {
        for (int i = 1; i <= count; i++) {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId((long) i)
                    .userId((long) (i % 10))
                    .totalAmount(BigDecimal.valueOf(10000 + i * 100))
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(LocalDateTime.now())
                    .build();
            orderEventProducer.publish(event);
        }
        return "Published " + count + " orders";
    }
}
