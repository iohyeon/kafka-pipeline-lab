package com.pipeline.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {
}
