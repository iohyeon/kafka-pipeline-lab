package com.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 느린 Consumer — Phase 3 리밸런싱 & LAG 실험용
 *
 * 의도적으로 처리를 느리게 해서:
 * 1. LAG(적체)이 쌓이는 것을 관찰
 * 2. max.poll.interval.ms 초과 시 리밸런싱이 발생하는 것을 확인
 * 3. Consumer가 죽었을 때 다른 Consumer가 파티션을 이어받는 것을 확인
 *
 * 별도 토픽(inventory.deduct.event-v1)을 사용해서 다른 Consumer와 충돌 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowConsumer {

    private final ObjectMapper objectMapper;

    // 처리 속도 조절 (ms). API로 동적 변경 가능.
    private volatile long processingDelayMs = 0;

    @KafkaListener(
            topics = "inventory.deduct.event-v1",
            groupId = "inventory-slow-group",
            containerFactory = "manualAckFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);

            log.info("[SlowConsumer] 수신 — partition={}, offset={}, key={}, delay={}ms",
                    record.partition(), record.offset(), record.key(), processingDelayMs);

            // 의도적 지연
            if (processingDelayMs > 0) {
                Thread.sleep(processingDelayMs);
            }

            log.info("[SlowConsumer] 처리 완료 — partition={}, offset={}", record.partition(), record.offset());
            ack.acknowledge();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SlowConsumer] 처리 중단 — partition={}, offset={}", record.partition(), record.offset());
        } catch (Exception e) {
            log.error("[SlowConsumer] 실패 — partition={}, offset={}, error={}",
                    record.partition(), record.offset(), e.getMessage());
        }
    }

    public void setProcessingDelayMs(long delayMs) {
        this.processingDelayMs = delayMs;
        log.info("[SlowConsumer] 처리 지연 변경 — {}ms", delayMs);
    }

    public long getProcessingDelayMs() {
        return processingDelayMs;
    }
}
