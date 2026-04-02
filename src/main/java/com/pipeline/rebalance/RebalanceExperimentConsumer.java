package com.pipeline.rebalance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Eager vs Cooperative 리밸런싱 비교 실험용 Consumer
 *
 * 6개의 컨슈머를 생성한다:
 * - eager-0, eager-1, eager-2  → rebalance-eager-group (RangeAssignor)
 * - coop-0, coop-1, coop-2    → rebalance-coop-group (CooperativeStickyAssignor)
 *
 * 토픽: rebalance.experiment-v1 (파티션 3개)
 *
 * 모든 컨슈머는 autoStartup=false → API로 수동 시작/중지.
 * 컨슈머 1개를 중지하면 리밸런싱이 발생하고,
 * RebalanceEventLog에 기록된 이벤트로 두 프로토콜의 차이를 관찰한다.
 *
 * 관찰 포인트:
 * - Eager: 모든 컨슈머의 REVOKED에 파티션이 포함됨 (stop-the-world)
 * - Cooperative: 영향받지 않는 컨슈머의 REVOKED는 빈 리스트 (무중단)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RebalanceExperimentConsumer {

    static final String TOPIC = "rebalance.experiment-v1";

    // ──────────────────────────────────────────────
    // Eager Group (RangeAssignor)
    // ──────────────────────────────────────────────

    @KafkaListener(
            id = "eager-0", topics = TOPIC,
            groupId = "rebalance-eager-group",
            containerFactory = "eagerRebalanceFactory",
            autoStartup = "false"
    )
    public void eager0(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("eager-0", record, ack);
    }

    @KafkaListener(
            id = "eager-1", topics = TOPIC,
            groupId = "rebalance-eager-group",
            containerFactory = "eagerRebalanceFactory",
            autoStartup = "false"
    )
    public void eager1(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("eager-1", record, ack);
    }

    @KafkaListener(
            id = "eager-2", topics = TOPIC,
            groupId = "rebalance-eager-group",
            containerFactory = "eagerRebalanceFactory",
            autoStartup = "false"
    )
    public void eager2(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("eager-2", record, ack);
    }

    // ──────────────────────────────────────────────
    // Cooperative Group (CooperativeStickyAssignor)
    // ──────────────────────────────────────────────

    @KafkaListener(
            id = "coop-0", topics = TOPIC,
            groupId = "rebalance-coop-group",
            containerFactory = "cooperativeRebalanceFactory",
            autoStartup = "false"
    )
    public void coop0(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("coop-0", record, ack);
    }

    @KafkaListener(
            id = "coop-1", topics = TOPIC,
            groupId = "rebalance-coop-group",
            containerFactory = "cooperativeRebalanceFactory",
            autoStartup = "false"
    )
    public void coop1(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("coop-1", record, ack);
    }

    @KafkaListener(
            id = "coop-2", topics = TOPIC,
            groupId = "rebalance-coop-group",
            containerFactory = "cooperativeRebalanceFactory",
            autoStartup = "false"
    )
    public void coop2(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("coop-2", record, ack);
    }

    // ──────────────────────────────────────────────
    // Static Membership Group (KIP-345)
    // CooperativeStickyAssignor + group.instance.id
    // 컨슈머가 떠나도 session.timeout.ms(45초) 동안 리밸런싱 없이 대기
    // 같은 instance.id로 복귀하면 같은 파티션을 즉시 돌려받음
    // ──────────────────────────────────────────────

    @KafkaListener(
            id = "static-0", topics = TOPIC,
            groupId = "rebalance-static-group",
            containerFactory = "staticMembershipFactory",
            properties = {"group.instance.id=static-instance-0"},
            autoStartup = "false"
    )
    public void static0(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("static-0", record, ack);
    }

    @KafkaListener(
            id = "static-1", topics = TOPIC,
            groupId = "rebalance-static-group",
            containerFactory = "staticMembershipFactory",
            properties = {"group.instance.id=static-instance-1"},
            autoStartup = "false"
    )
    public void static1(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("static-1", record, ack);
    }

    @KafkaListener(
            id = "static-2", topics = TOPIC,
            groupId = "rebalance-static-group",
            containerFactory = "staticMembershipFactory",
            properties = {"group.instance.id=static-instance-2"},
            autoStartup = "false"
    )
    public void static2(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        processAndAck("static-2", record, ack);
    }

    // ──────────────────────────────────────────────

    private void processAndAck(String consumerId, ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        log.info("[{}] 처리 — partition={}, offset={}, key={}",
                consumerId, record.partition(), record.offset(), record.key());
        ack.acknowledge();
    }
}
