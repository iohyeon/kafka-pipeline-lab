package com.pipeline.dlq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DLQ (Dead Letter Queue) Publisher
 *
 * 처리 실패한 메시지를 DLQ 토픽으로 전송한다.
 *
 * DLQ 메시지에는 원본 정보를 헤더에 담는다:
 * - X-Original-Topic: 원래 토픽명
 * - X-Original-Partition: 원래 파티션 번호
 * - X-Original-Offset: 원래 오프셋
 * - X-Error-Message: 실패 사유
 * - X-Error-Timestamp: 실패 시각
 * - X-Retry-Count: 재시도 횟수
 *
 * 흐름:
 * ┌──────────────┐     처리 실패     ┌──────────────┐
 * │   Consumer    │ ─────────────→  │  DLQ Publisher │
 * │   (재시도 3회)│                  │               │
 * └──────────────┘                  └──────┬───────┘
 *                                          │
 *                                     send (동기)
 *                                          │
 *                                          ▼
 *                                  ┌──────────────┐
 *                                  │ pipeline.dlq │
 *                                  │    -v1       │
 *                                  └──────────────┘
 *                                          │
 *                                     후처리/모니터링
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqPublisher {

    private static final String DLQ_TOPIC = "pipeline.dlq-v1";

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;

    public void sendToDlq(ConsumerRecord<String, byte[]> originalRecord,
                          Exception exception, int retryCount) {
        try {
            // 원본 메시지를 DLQ 토픽으로 전송 (헤더에 에러 정보 추가)
            var producerRecord = new org.apache.kafka.clients.producer.ProducerRecord<String, Object>(
                    DLQ_TOPIC,
                    null,  // partition (null = 기본 분배)
                    originalRecord.key(),
                    new String(originalRecord.value(), StandardCharsets.UTF_8)
            );

            // 원본 정보 헤더
            producerRecord.headers().add(new RecordHeader("X-Original-Topic",
                    originalRecord.topic().getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("X-Original-Partition",
                    String.valueOf(originalRecord.partition()).getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("X-Original-Offset",
                    String.valueOf(originalRecord.offset()).getBytes(StandardCharsets.UTF_8)));

            // 에러 정보 헤더
            String errorMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            producerRecord.headers().add(new RecordHeader("X-Error-Message",
                    errorMsg.getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("X-Error-Timestamp",
                    LocalDateTime.now().toString().getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("X-Retry-Count",
                    String.valueOf(retryCount).getBytes(StandardCharsets.UTF_8)));

            // 동기 전송 (DLQ 전송 실패도 알아야 하므로)
            SendResult<String, Object> result = acksAllKafkaTemplate
                    .send(producerRecord)
                    .get(10, TimeUnit.SECONDS);

            var metadata = result.getRecordMetadata();
            log.warn("[DLQ] 메시지 격리 완료 — dlqPartition={}, dlqOffset={}, " +
                            "originalTopic={}, originalPartition={}, originalOffset={}, error={}",
                    metadata.partition(), metadata.offset(),
                    originalRecord.topic(), originalRecord.partition(), originalRecord.offset(),
                    errorMsg);

        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            // DLQ 전송마저 실패하면 로그로만 남긴다 (최후의 수단)
            log.error("[DLQ] DLQ 전송 실패! 원본 메시지가 유실될 수 있음 — " +
                            "topic={}, partition={}, offset={}, error={}",
                    originalRecord.topic(), originalRecord.partition(),
                    originalRecord.offset(), e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
