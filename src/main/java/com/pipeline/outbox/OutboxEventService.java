package com.pipeline.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 이벤트 저장 서비스
 *
 * 비즈니스 트랜잭션 안에서 호출된다.
 * 이 메서드가 비즈니스 로직과 같은 @Transactional 안에서 실행되므로
 * DB 커밋 시 비즈니스 데이터 + Outbox 이벤트가 원자적으로 저장된다.
 *
 * 사용 예:
 *   @Transactional
 *   public void createOrder(Order order) {
 *       orderRepository.save(order);
 *       outboxEventService.save("order.created.event-v1", order.getId(), "ORDER_CREATED", event);
 *       // → 같은 TX에서 둘 다 커밋
 *   }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox에 이벤트 저장 (비즈니스 TX 안에서 호출)
     */
    @Transactional
    public OutboxEvent save(String topic, String partitionKey, String eventType, Object payload) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox 페이로드 직렬화 실패", e);
        }

        OutboxEvent outboxEvent = OutboxEvent.create(topic, partitionKey, eventType, jsonPayload);
        OutboxEvent saved = outboxEventRepository.save(outboxEvent);

        log.info("[Outbox] 이벤트 저장 — id={}, topic={}, key={}, type={}",
                saved.getId(), topic, partitionKey, eventType);

        return saved;
    }
}
