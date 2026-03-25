package com.pipeline.outbox;

/**
 * Outbox 이벤트 상태
 *
 * PENDING   → 아직 Kafka에 발행되지 않음 (릴레이가 처리할 대상)
 * PUBLISHED → Kafka에 성공적으로 발행됨
 * FAILED    → 발행 실패 (재시도 또는 수동 확인 필요)
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
