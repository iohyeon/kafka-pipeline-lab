package com.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Batch Consumer — Phase 3
 *
 * 여러 메시지를 한 번에 받아서 처리하는 Consumer.
 *
 * 단건 처리 vs 배치 처리:
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ 단건: poll() → 1건 처리 → ACK → poll() → 1건 처리 → ACK        │
 * │ 배치: poll() → N건 한번에 처리 → ACK → poll() → N건 처리 → ACK  │
 * │                                                                  │
 * │ 배치가 유리한 경우:                                               │
 * │ - DB에 bulk insert/update 할 때                                  │
 * │ - 네트워크 호출을 묶어서 할 때                                    │
 * │ - 처리량(throughput)이 중요할 때                                  │
 * └──────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchOrderEventConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "order.created.event-v1",
            groupId = "order-batch-group",
            containerFactory = "batchManualAckFactory"
    )
    public void consumeBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        log.info("[BatchConsumer] 배치 수신 — {}건", records.size());

        int successCount = 0;
        int failCount = 0;

        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                OrderCreatedEvent event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);

                log.debug("[BatchConsumer] 처리 — partition={}, offset={}, orderId={}",
                        record.partition(), record.offset(), event.getOrderId());

                processBatchItem(event);
                successCount++;

            } catch (Exception e) {
                failCount++;
                log.error("[BatchConsumer] 실패 — partition={}, offset={}, error={}",
                        record.partition(), record.offset(), e.getMessage());
                // Phase 6에서 DLQ 전송 로직 추가
            }
        }

        // 전체 배치 처리 후 한 번에 ACK
        ack.acknowledge();
        log.info("[BatchConsumer] 배치 ACK 완료 — success={}, fail={}", successCount, failCount);
    }

    private void processBatchItem(OrderCreatedEvent event) {
        // 실제로는 bulk insert, 외부 API batch call 등
        log.debug("[BatchConsumer] 처리 중 — orderId={}", event.getOrderId());
    }
}
