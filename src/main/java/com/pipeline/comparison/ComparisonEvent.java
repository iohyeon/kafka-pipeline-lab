package com.pipeline.comparison;

import java.time.LocalDateTime;

/**
 * BEFORE_COMMIT 방식에서 사용하는 도메인 이벤트
 */
public record ComparisonEvent(
        Long orderId,
        String eventType,
        String method,
        String eventId,
        LocalDateTime occurredAt
) {
}
