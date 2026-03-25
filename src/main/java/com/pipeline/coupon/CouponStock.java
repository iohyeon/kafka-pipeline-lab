package com.pipeline.coupon;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 쿠폰 재고 — 선착순 수량 제한
 *
 * 핵심: Kafka 파티션 순차 처리 + DB 재고 차감으로 동시성 제어
 *
 * couponId가 Partition key → 같은 쿠폰의 모든 발급 요청은 같은 파티션
 * → 같은 Consumer가 순차 처리 → DB 수량 체크가 순차적으로 실행
 * → 초과 발급 방지
 */
@Entity
@Table(name = "coupon_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponStock {

    @Id
    private Long couponId;

    @Column(nullable = false)
    private String couponName;

    @Column(nullable = false)
    private int totalQuantity;

    @Column(nullable = false)
    private int issuedQuantity;

    public static CouponStock create(Long couponId, String couponName, int totalQuantity) {
        CouponStock stock = new CouponStock();
        stock.couponId = couponId;
        stock.couponName = couponName;
        stock.totalQuantity = totalQuantity;
        stock.issuedQuantity = 0;
        return stock;
    }

    /**
     * 쿠폰 발급 시도
     * @return true = 발급 성공, false = 재고 소진
     */
    public boolean tryIssue() {
        if (issuedQuantity >= totalQuantity) {
            return false;
        }
        issuedQuantity++;
        return true;
    }

    public int remainingQuantity() {
        return totalQuantity - issuedQuantity;
    }
}
