package com.pipeline.comparison;

/**
 * AFTER_COMMIT 방식에서 사용하는 이벤트
 * TX 커밋 후 Kafka 즉시 발행 시도를 위해 사용
 */
public record ComparisonAfterCommitEvent(
        Long orderId,
        String eventId,
        Long outboxId
) {
}
