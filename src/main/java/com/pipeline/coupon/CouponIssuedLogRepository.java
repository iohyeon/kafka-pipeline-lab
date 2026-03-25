package com.pipeline.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssuedLogRepository extends JpaRepository<CouponIssuedLog, Long> {

    long countByCouponId(Long couponId);

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);
}
