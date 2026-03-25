package com.pipeline.api;

import com.pipeline.coupon.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 7 — 선착순 쿠폰 발급 실험 API
 */
@RestController
@RequestMapping("/api/experiment/coupon")
@RequiredArgsConstructor
public class CouponExperimentController {

    private final KafkaTemplate<String, Object> acksAllKafkaTemplate;
    private final CouponStockRepository couponStockRepository;
    private final CouponIssuedLogRepository couponIssuedLogRepository;

    /**
     * 쿠폰 생성 (재고 초기화)
     * POST /api/experiment/coupon/create?couponId=1&name=신규가입쿠폰&quantity=100
     */
    @PostMapping("/create")
    public Map<String, Object> createCoupon(
            @RequestParam(defaultValue = "1") Long couponId,
            @RequestParam(defaultValue = "신규가입쿠폰") String name,
            @RequestParam(defaultValue = "100") int quantity) {

        couponStockRepository.findById(couponId).ifPresent(couponStockRepository::delete);
        CouponStock stock = CouponStock.create(couponId, name, quantity);
        couponStockRepository.save(stock);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponId", couponId);
        result.put("name", name);
        result.put("totalQuantity", quantity);
        result.put("issuedQuantity", 0);
        return result;
    }

    /**
     * 동시성 테스트 — N명이 동시에 쿠폰 발급 요청
     * POST /api/experiment/coupon/concurrent-test?couponId=1&userCount=200&threads=50
     *
     * userCount명의 유저가 threads개의 스레드로 동시에 Kafka에 발급 요청을 보낸다.
     * couponId가 key → 같은 파티션 → 순차 처리 → 수량 초과 방지
     */
    @PostMapping("/concurrent-test")
    public Map<String, Object> concurrentTest(
            @RequestParam(defaultValue = "1") Long couponId,
            @RequestParam(defaultValue = "200") int userCount,
            @RequestParam(defaultValue = "50") int threads) throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger publishedCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        for (int i = 1; i <= userCount; i++) {
            final long userId = i;
            executor.submit(() -> {
                try {
                    CouponEvent event = CouponEvent.builder()
                            .couponId(couponId)
                            .userId(userId)
                            .eventId(UUID.randomUUID().toString())
                            .requestedAt(LocalDateTime.now())
                            .build();

                    // key = couponId → 같은 쿠폰의 모든 요청은 같은 파티션!
                    acksAllKafkaTemplate.send(
                            "coupon.issue.request-v1",
                            String.valueOf(couponId),   // ← couponId가 key
                            event
                    );
                    publishedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long publishTime = System.currentTimeMillis() - startTime;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponId", couponId);
        result.put("userCount", userCount);
        result.put("threads", threads);
        result.put("published", publishedCount.get());
        result.put("publishTimeMs", publishTime);
        result.put("확인방법", "30초 후 GET /api/experiment/coupon/result?couponId=" + couponId);
        return result;
    }

    /**
     * 테스트 결과 확인
     * GET /api/experiment/coupon/result?couponId=1
     */
    @GetMapping("/result")
    public Map<String, Object> getResult(@RequestParam(defaultValue = "1") Long couponId) {
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰 없음: " + couponId));

        long issuedLogCount = couponIssuedLogRepository.countByCouponId(couponId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponId", couponId);
        result.put("couponName", stock.getCouponName());
        result.put("totalQuantity", stock.getTotalQuantity());
        result.put("issuedQuantity", stock.getIssuedQuantity());
        result.put("remainingQuantity", stock.remainingQuantity());
        result.put("issuedLogCount", issuedLogCount);
        result.put("정합성", stock.getIssuedQuantity() == issuedLogCount ? "✅ 일치" : "❌ 불일치!");
        result.put("초과발급", stock.getIssuedQuantity() > stock.getTotalQuantity() ? "❌ 초과!" : "✅ 정상");
        return result;
    }

    /**
     * 쿠폰 재고 상태 조회
     * GET /api/experiment/coupon/stock?couponId=1
     */
    @GetMapping("/stock")
    public Map<String, Object> getStock(@RequestParam(defaultValue = "1") Long couponId) {
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("쿠폰 없음: " + couponId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponId", couponId);
        result.put("totalQuantity", stock.getTotalQuantity());
        result.put("issuedQuantity", stock.getIssuedQuantity());
        result.put("remainingQuantity", stock.remainingQuantity());
        return result;
    }
}
