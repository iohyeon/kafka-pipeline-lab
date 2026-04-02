package com.pipeline.rebalance;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 리밸런싱 실험용 Factory 설정 — 3가지 전략 비교
 *
 * 1. eagerRebalanceFactory        → RangeAssignor (Eager 프로토콜)
 * 2. cooperativeRebalanceFactory  → CooperativeStickyAssignor (Incremental Cooperative)
 * 3. staticMembershipFactory      → CooperativeStickyAssignor + group.instance.id (Static Membership, KIP-345)
 *
 * Static Membership의 핵심:
 * - group.instance.id를 설정하면 컨슈머가 "정적 멤버"가 된다
 * - 컨슈머가 떠나도 session.timeout.ms 동안 브로커가 파티션 할당을 유지
 * - 같은 instance.id로 돌아오면 리밸런싱 없이 같은 파티션을 즉시 돌려받음
 * - group.instance.id는 @KafkaListener의 properties 속성으로 컨슈머별 개별 설정
 */
@Slf4j
@Configuration
public class RebalanceExperimentConfig {

    private final String bootstrapServers;
    private final RebalanceEventLog eventLog;

    public RebalanceExperimentConfig(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            RebalanceEventLog eventLog) {
        this.bootstrapServers = bootstrapServers;
        this.eventLog = eventLog;
    }

    private Map<String, Object> baseConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 빠른 리밸런싱 감지를 위한 설정
        configs.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);    // 10초
        configs.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);  // 3초
        configs.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30000);  // 30초
        return configs;
    }

    // ──────────────────────────────────────────────
    // Eager (RangeAssignor) — 전통 방식
    // 리밸런싱 시 모든 컨슈머가 파티션을 전부 반납 후 재할당
    // ──────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> eagerRebalanceFactory() {
        Map<String, Object> configs = baseConfigs();
        configs.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.RangeAssignor");

        ConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<>(configs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setConsumerRebalanceListener(rebalanceListener("EAGER"));
        return factory;
    }

    // ──────────────────────────────────────────────
    // Cooperative (CooperativeStickyAssignor) — Kafka 2.4+
    // 변경이 필요한 파티션만 단계적으로 반납·재할당
    // ──────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> cooperativeRebalanceFactory() {
        Map<String, Object> configs = baseConfigs();
        configs.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");

        ConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<>(configs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setConsumerRebalanceListener(rebalanceListener("COOPERATIVE"));
        return factory;
    }

    // ──────────────────────────────────────────────
    // Static Membership (KIP-345) — Kafka 2.3+
    // group.instance.id 설정 시 session.timeout.ms 동안 파티션 할당 유지
    // 컨슈머가 돌아오면 리밸런싱 없이 같은 파티션 복귀
    // ──────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> staticMembershipFactory() {
        Map<String, Object> configs = baseConfigs();
        configs.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        // Static Membership: session.timeout.ms를 길게 설정 = 복귀 대기 시간
        // 컨슈머가 떠나도 45초 동안 파티션 할당을 유지한다
        configs.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);   // 45초
        configs.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 5000); // 5초
        // group.instance.id는 @KafkaListener(properties=...)로 컨슈머별 설정

        ConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<>(configs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setConsumerRebalanceListener(rebalanceListener("STATIC"));
        return factory;
    }

    private ConsumerRebalanceListener rebalanceListener(String protocol) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                String thread = Thread.currentThread().getName();
                String consumerId = extractConsumerId(thread);
                eventLog.log(protocol, consumerId, "REVOKED", partitions);
                log.info("[{}] {} — REVOKED {} (thread={})",
                        protocol, consumerId, formatPartitions(partitions), thread);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                String thread = Thread.currentThread().getName();
                String consumerId = extractConsumerId(thread);
                eventLog.log(protocol, consumerId, "ASSIGNED", partitions);
                log.info("[{}] {} — ASSIGNED {} (thread={})",
                        protocol, consumerId, formatPartitions(partitions), thread);
            }
        };
    }

    /**
     * Spring Kafka 스레드 이름 형식: "{containerId}-0-C-1"
     * containerId 부분만 추출 (예: "eager-0-0-C-1" → "eager-0")
     */
    private static String extractConsumerId(String threadName) {
        int idx = threadName.indexOf("-0-C-");
        return idx > 0 ? threadName.substring(0, idx) : threadName;
    }

    private static String formatPartitions(Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) return "[]";
        return partitions.stream()
                .map(tp -> "P" + tp.partition())
                .sorted()
                .toList()
                .toString();
    }
}
