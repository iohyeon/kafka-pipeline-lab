package com.pipeline.producer;

import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Partition Key 라우팅 검증 Producer
 *
 * 같은 key로 여러 번 보내면 같은 파티션에 가는지,
 * 다른 key는 어떻게 분배되는지를 실험한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyRoutingProducer {

    private static final String TOPIC = "order.created.event-v1";

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    /**
     * 특정 key로 count번 발행 → 전부 같은 파티션에 가는지 확인
     *
     * @return key별 파티션 매핑 결과
     */
    public Map<String, String> verifyKeyRouting(Long[] orderIds, int repeatPerKey) {
        Map<String, String> results = new ConcurrentHashMap<>();
        int totalMessages = orderIds.length * repeatPerKey;
        CountDownLatch latch = new CountDownLatch(totalMessages);

        for (Long orderId : orderIds) {
            String key = String.valueOf(orderId);
            for (int i = 0; i < repeatPerKey; i++) {
                OrderCreatedEvent event = OrderCreatedEvent.builder()
                        .orderId(orderId)
                        .userId(1L)
                        .totalAmount(BigDecimal.valueOf(10000))
                        .eventId(UUID.randomUUID().toString())
                        .occurredAt(LocalDateTime.now())
                        .build();

                int attempt = i + 1;
                CompletableFuture<SendResult<String, Object>> future =
                        acksAllKafkaTemplate.send(TOPIC, key, event);

                future.whenComplete((result, ex) -> {
                    if (ex == null) {
                        var metadata = result.getRecordMetadata();
                        String entry = String.format("partition=%d, offset=%d",
                                metadata.partition(), metadata.offset());

                        String mapKey = String.format("key=%s, attempt=%d", key, attempt);
                        results.put(mapKey, entry);

                        log.info("[KeyRouting] key={}, attempt={} → partition={}, offset={}",
                                key, attempt, metadata.partition(), metadata.offset());
                    }
                    latch.countDown();
                });
            }
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new LinkedHashMap<>(results);
    }
}
