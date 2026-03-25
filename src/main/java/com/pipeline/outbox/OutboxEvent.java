package com.pipeline.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 테이블 Entity
 *
 * 핵심 원리:
 * 비즈니스 로직(주문 생성 등)과 같은 트랜잭션 안에서 이 테이블에 이벤트를 저장한다.
 * → DB TX 커밋 = 비즈니스 데이터 + Outbox 이벤트가 동시에 확정
 * → 별도 릴레이(Polling)가 이 테이블을 읽어서 Kafka로 발행
 * → 발행 후 상태를 PUBLISHED로 변경
 *
 * ┌──────────────────────────────────────────────────┐
 * │                    TX 1                           │
 * │  ┌───────┐  ┌────────┐                           │
 * │  │ order │  │ outbox │  ← 같은 TX에 저장          │
 * │  │ 테이블 │  │ 테이블  │                           │
 * │  └───────┘  └────────┘                           │
 * └──────────────────────────────────────────────────┘
 *                    │
 *            Polling (스케줄러)
 *                    │
 *                    ▼
 *          ┌──────────────────┐
 *          │     Kafka        │
 *          └──────────────────┘
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Kafka 토픽명
     */
    @Column(nullable = false, length = 200)
    private String topic;

    /**
     * Partition Key (orderId, couponId 등)
     */
    @Column(name = "partition_key", length = 100)
    private String partitionKey;

    /**
     * 이벤트 페이로드 (JSON)
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * 이벤트 타입 (ORDER_CREATED, COUPON_ISSUED 등)
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * 발행 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    /**
     * 재시도 횟수
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * 생성 시각
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 발행 완료 시각
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * 실패 사유
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    // ──────────────────────────────────────────────
    // 생성 팩토리 메서드
    // ──────────────────────────────────────────────

    public static OutboxEvent create(String topic, String partitionKey,
                                     String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.topic = topic;
        event.partitionKey = partitionKey;
        event.eventType = eventType;
        event.payload = payload;
        event.status = OutboxStatus.PENDING;
        event.retryCount = 0;
        event.createdAt = LocalDateTime.now();
        return event;
    }

    // ──────────────────────────────────────────────
    // 상태 변경 메서드
    // ──────────────────────────────────────────────

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = OutboxStatus.FAILED;
        this.retryCount++;
        this.errorMessage = errorMessage;
    }

    public void markRetry() {
        this.status = OutboxStatus.PENDING;
        this.retryCount++;
    }
}
