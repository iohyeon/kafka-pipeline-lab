package com.pipeline.coupon;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponEvent {

    private Long couponId;
    private Long userId;
    private String eventId;
    private LocalDateTime requestedAt;
}
