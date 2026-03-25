package com.pipeline.coupon;

import com.pipeline.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 발급 서비스 — Consumer에서 호출
 *
 * 같은 @Transactional 안에서:
 * 1. 쿠폰 재고 확인 + 차감
 * 2. 발급 이력 저장
 * 3. 멱등성 기록 (event_handled)
 *
 * → 셋 중 하나라도 실패하면 전부 롤백
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssuedLogRepository couponIssuedLogRepository;
    private final IdempotencyService idempotencyService;

    @Transactional
    public CouponIssueResult issue(CouponEvent event) {
        Long couponId = event.getCouponId();
        Long userId = event.getUserId();
        String eventId = event.getEventId();

        // 1. 이미 발급받은 유저인지 확인 (1인 1쿠폰)
        if (couponIssuedLogRepository.existsByCouponIdAndUserId(couponId, userId)) {
            log.warn("[CouponService] 이미 발급된 유저 — couponId={}, userId={}", couponId, userId);
            return CouponIssueResult.ALREADY_ISSUED;
        }

        // 2. 재고 확인 + 차감
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰 없음: " + couponId));

        if (!stock.tryIssue()) {
            log.warn("[CouponService] 재고 소진 — couponId={}, userId={}, issued={}/{}",
                    couponId, userId, stock.getIssuedQuantity(), stock.getTotalQuantity());
            return CouponIssueResult.OUT_OF_STOCK;
        }

        // 3. 발급 이력 저장
        couponIssuedLogRepository.save(CouponIssuedLog.of(couponId, userId, eventId));

        // 4. 멱등성 기록 (같은 TX)
        idempotencyService.markHandled(eventId, "COUPON_ISSUED");

        log.info("[CouponService] 발급 성공 — couponId={}, userId={}, remaining={}",
                couponId, userId, stock.remainingQuantity());

        return CouponIssueResult.SUCCESS;
    }

    public enum CouponIssueResult {
        SUCCESS,
        OUT_OF_STOCK,
        ALREADY_ISSUED
    }
}
