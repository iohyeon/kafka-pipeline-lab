package com.pipeline.order;

import com.pipeline.event.OrderCreatedEvent;
import com.pipeline.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 주문 서비스 — Outbox 패턴 적용
 *
 * 핵심: createOrder()는 @Transactional 안에서
 * 1. 주문 데이터 저장 (시뮬레이션)
 * 2. Outbox에 이벤트 저장
 * 을 동시에 수행한다.
 *
 * Kafka 발행은 여기서 하지 않는다!
 * → OutboxRelayService가 별도로 Outbox를 읽어서 Kafka로 발행한다.
 *
 * 이렇게 하면:
 * - DB 커밋 성공 = 주문 + 이벤트 모두 확정
 * - DB 롤백 = 주문 + 이벤트 모두 롤백
 * - Kafka 장애와 무관하게 DB 트랜잭션은 성공
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String ORDER_TOPIC = "order.created.event-v1";

    private final OutboxEventService outboxEventService;

    @Transactional
    public Long createOrder(Long userId, BigDecimal totalAmount) {
        // 1. 주문 데이터 저장 (실제로는 OrderRepository.save())
        Long orderId = System.currentTimeMillis() % 100000;  // 시뮬레이션
        log.info("[OrderService] 주문 생성 — orderId={}, userId={}, amount={}",
                orderId, userId, totalAmount);

        // 2. Outbox에 이벤트 저장 — 같은 TX!
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(orderId)
                .userId(userId)
                .totalAmount(totalAmount)
                .eventId(UUID.randomUUID().toString())
                .occurredAt(LocalDateTime.now())
                .build();

        outboxEventService.save(
                ORDER_TOPIC,
                String.valueOf(orderId),    // partition key
                "ORDER_CREATED",
                event
        );

        log.info("[OrderService] 주문 + Outbox 저장 완료 (같은 TX) — orderId={}", orderId);

        return orderId;
    }
}
