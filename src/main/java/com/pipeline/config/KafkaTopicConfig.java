package com.pipeline.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic 설계 — Phase 1
 *
 * 실무 기준:
 * - Partition 수 = Consumer 인스턴스 수와 맞춤 (3개)
 * - Replication Factor = Broker 수와 동일 (3개) → 1대 죽어도 데이터 유실 없음
 * - Min ISR = 2 → acks=all과 조합하면 최소 2개 복제본이 확인해야 쓰기 성공
 */
@Configuration
public class KafkaTopicConfig {

    /**
     * 주문 생성 이벤트 토픽
     * - key: orderId → 같은 주문의 이벤트는 같은 파티션 → 순서 보장
     */
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name("order.created.event-v1")
                .partitions(3)
                .replicas(3)
                .config("min.insync.replicas", "2")
                .config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000L))  // 7일
                .build();
    }

    /**
     * 쿠폰 발급 요청 토픽
     * - key: couponId → 같은 쿠폰에 대한 발급 요청은 같은 파티션 → 순차 처리로 동시성 제어
     */
    @Bean
    public NewTopic couponIssueRequestTopic() {
        return TopicBuilder.name("coupon.issue.request-v1")
                .partitions(3)
                .replicas(3)
                .config("min.insync.replicas", "2")
                .build();
    }

    /**
     * 재고 차감 이벤트 토픽
     * - key: productId → 같은 상품의 재고 변경은 순서 보장
     */
    @Bean
    public NewTopic inventoryDeductTopic() {
        return TopicBuilder.name("inventory.deduct.event-v1")
                .partitions(3)
                .replicas(3)
                .config("min.insync.replicas", "2")
                .build();
    }

    /**
     * 리밸런싱 실험 토픽 — Eager vs Cooperative 비교용
     * - 파티션 3개 = 컨슈머 3개와 1:1 매핑
     */
    @Bean
    public NewTopic rebalanceExperimentTopic() {
        return TopicBuilder.name("rebalance.experiment-v1")
                .partitions(3)
                .replicas(3)
                .config("min.insync.replicas", "2")
                .build();
    }

    /**
     * DLQ (Dead Letter Queue) — Phase 6에서 활용
     * - 처리 실패한 메시지가 격리되는 토픽
     * - 파티션 1개로 충분 (실패 메시지는 소량)
     */
    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name("pipeline.dlq-v1")
                .partitions(1)
                .replicas(3)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000L))  // 30일 (오래 보관)
                .build();
    }
}
