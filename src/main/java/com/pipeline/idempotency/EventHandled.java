package com.pipeline.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 멱등성 처리 테이블 — 이미 처리한 이벤트를 기록
 *
 * 핵심 원리 (케브님 멘토링):
 * "비즈니스 로직 + event_handled INSERT를 같은 TX로 묶는다."
 * → 비즈니스 성공 = 멱등 기록 성공 (같은 TX)
 * → 비즈니스 실패 = 멱등 기록도 롤백 → 재처리 가능
 *
 * 왜 Redis가 아닌 DB인가?
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Redis SET NX의 문제:                                          │
 * │  ① SET NX 성공 (이벤트 "처리 시작" 표시)                       │
 * │  ② 비즈니스 로직 실행... 실패! ❌                               │
 * │  ③ Redis에는 "처리됨"으로 남아있음                              │
 * │  ④ 재처리 시도 → SET NX 실패 → 이벤트가 영원히 처리 안 됨     │
 * │                                                                 │
 * │  DB event_handled의 장점:                                       │
 * │  ① 비즈니스 로직 + INSERT를 같은 TX                            │
 * │  ② 비즈니스 실패 → TX 롤백 → event_handled도 롤백             │
 * │  ③ 재처리 시 event_handled에 없음 → 정상 재처리               │
 * └─────────────────────────────────────────────────────────────────┘
 */
@Entity
@Table(name = "event_handled", indexes = {
        @Index(name = "idx_event_handled_event_id", columnList = "event_id", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventHandled {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이벤트 고유 ID (UUID) — Producer가 발행 시 생성
     * UNIQUE 제약 조건으로 중복 INSERT 방지
     */
    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    private String eventId;

    /**
     * 이벤트 타입 (ORDER_CREATED, COUPON_ISSUED 등)
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * 처리 완료 시각
     */
    @Column(name = "handled_at", nullable = false)
    private LocalDateTime handledAt;

    public static EventHandled of(String eventId, String eventType) {
        EventHandled handled = new EventHandled();
        handled.eventId = eventId;
        handled.eventType = eventType;
        handled.handledAt = LocalDateTime.now();
        return handled;
    }
}
