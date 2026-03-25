package com.pipeline.coupon;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 쿠폰 발급 이력
 */
@Entity
@Table(name = "coupon_issued_log", indexes = {
        @Index(name = "idx_coupon_issued_coupon_user", columnList = "coupon_id, user_id", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssuedLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    public static CouponIssuedLog of(Long couponId, Long userId, String eventId) {
        CouponIssuedLog log = new CouponIssuedLog();
        log.couponId = couponId;
        log.userId = userId;
        log.eventId = eventId;
        log.issuedAt = LocalDateTime.now();
        return log;
    }
}
