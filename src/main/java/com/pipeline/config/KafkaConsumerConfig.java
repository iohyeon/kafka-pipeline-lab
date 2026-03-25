package com.pipeline.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer 설정 — Phase 3
 *
 * 3가지 ListenerContainerFactory를 제공한다:
 *
 * 1. manualAckFactory — 수동 ACK (기본, 가장 안전)
 *    처리 완료 후 ack.acknowledge()를 직접 호출해야 offset 커밋.
 *    실패 시 offset 커밋 안 되므로 재처리 가능.
 *
 * 2. batchManualAckFactory — 배치 수동 ACK
 *    여러 메시지를 한 번에 받아서 처리. 대량 처리에 적합.
 *    전체 배치 처리 완료 후 한 번에 ACK.
 *
 * 3. autoCommitFactory — 자동 커밋 (비교 실험용, 실무 비권장)
 *    poll() 간격마다 자동으로 offset 커밋.
 *    처리 실패해도 offset이 이미 커밋됨 → 메시지 유실 위험.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Map<String, Object> baseConsumerConfigs(String groupId) {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 리밸런싱 감지를 빠르게 하기 위한 설정
        configs.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        configs.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        configs.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 120000);
        configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return configs;
    }

    // ──────────────────────────────────────────────
    // 1. Manual ACK (단건) — 기본, 가장 안전
    // ──────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> manualAckFactory() {
        Map<String, Object> configs = baseConsumerConfigs("order-inventory-group");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConsumerFactory<String, byte[]> factory = new DefaultKafkaConsumerFactory<>(configs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> container =
                new ConcurrentKafkaListenerContainerFactory<>();
        container.setConsumerFactory(factory);
        container.setConcurrency(3);  // 파티션 수와 동일
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return container;
    }

    // ──────────────────────────────────────────────
    // 2. Batch Manual ACK — 배치 처리 + 수동 ACK
    // ──────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> batchManualAckFactory() {
        Map<String, Object> configs = baseConsumerConfigs("order-batch-group");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);  // 한 번에 최대 50건

        ConsumerFactory<String, byte[]> factory = new DefaultKafkaConsumerFactory<>(configs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> container =
                new ConcurrentKafkaListenerContainerFactory<>();
        container.setConsumerFactory(factory);
        container.setConcurrency(3);
        container.setBatchListener(true);  // 배치 모드 ON
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return container;
    }

    // ──────────────────────────────────────────────
    // 3. Auto Commit — 비교 실험용 (실무 비권장)
    // ──────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> autoCommitFactory() {
        Map<String, Object> configs = baseConsumerConfigs("order-autocommit-group");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        configs.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);  // 5초마다 자동 커밋

        ConsumerFactory<String, byte[]> factory = new DefaultKafkaConsumerFactory<>(configs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> container =
                new ConcurrentKafkaListenerContainerFactory<>();
        container.setConsumerFactory(factory);
        container.setConcurrency(3);
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return container;
    }
}
