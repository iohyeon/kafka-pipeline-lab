package com.pipeline.dlq;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DLQ Consumer — 실패 메시지 모니터링 및 후처리
 *
 * DLQ에 들어온 메시지를 읽어서:
 * 1. 로그로 상세 정보 출력 (모니터링)
 * 2. 필요하면 재처리 로직 실행
 * 3. 또는 수동 확인을 위해 기록만 남김
 *
 * 실무에서는:
 * - Slack/Teams 알림 전송
 * - DLQ 전용 대시보드에 기록
 * - 일정 시간 후 자동 재처리 시도
 */
@Slf4j
@Component
public class DlqConsumer {

    // 최근 DLQ 메시지를 메모리에 보관 (모니터링 API용)
    private final List<Map<String, String>> recentDlqMessages = new ArrayList<>();
    private static final int MAX_RECENT = 50;

    @KafkaListener(
            topics = "pipeline.dlq-v1",
            groupId = "dlq-monitor-group",
            containerFactory = "manualAckFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String originalTopic = getHeader(record, "X-Original-Topic");
        String originalPartition = getHeader(record, "X-Original-Partition");
        String originalOffset = getHeader(record, "X-Original-Offset");
        String errorMessage = getHeader(record, "X-Error-Message");
        String errorTimestamp = getHeader(record, "X-Error-Timestamp");
        String retryCount = getHeader(record, "X-Retry-Count");

        log.error("[DLQ Monitor] 실패 메시지 수신 — " +
                        "originalTopic={}, originalPartition={}, originalOffset={}, " +
                        "retryCount={}, error={}, timestamp={}",
                originalTopic, originalPartition, originalOffset,
                retryCount, errorMessage, errorTimestamp);

        // 메모리에 보관 (API 조회용)
        Map<String, String> dlqEntry = new LinkedHashMap<>();
        dlqEntry.put("originalTopic", originalTopic);
        dlqEntry.put("originalPartition", originalPartition);
        dlqEntry.put("originalOffset", originalOffset);
        dlqEntry.put("retryCount", retryCount);
        dlqEntry.put("errorMessage", errorMessage);
        dlqEntry.put("errorTimestamp", errorTimestamp);
        dlqEntry.put("key", record.key());
        dlqEntry.put("payload", new String(record.value(), StandardCharsets.UTF_8));

        synchronized (recentDlqMessages) {
            recentDlqMessages.add(0, dlqEntry);
            if (recentDlqMessages.size() > MAX_RECENT) {
                recentDlqMessages.remove(recentDlqMessages.size() - 1);
            }
        }

        ack.acknowledge();
    }

    public List<Map<String, String>> getRecentDlqMessages() {
        synchronized (recentDlqMessages) {
            return new ArrayList<>(recentDlqMessages);
        }
    }

    private String getHeader(ConsumerRecord<String, byte[]> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : "N/A";
    }
}
