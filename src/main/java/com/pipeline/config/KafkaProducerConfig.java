package com.pipeline.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Producer 설정 — Phase 2
 *
 * acks 설정별 3가지 Producer를 Bean으로 등록해서
 * 동작 차이를 실험할 수 있게 한다.
 *
 * ┌──────────┬──────────────────────────────────────────────────────────────┐
 * │ acks=0   │ 브로커 응답을 기다리지 않음. 가장 빠르지만 유실 가능.         │
 * │ acks=1   │ Leader만 확인. Leader가 죽으면 유실 가능.                    │
 * │ acks=all │ 모든 ISR이 확인. 가장 안전하지만 가장 느림.                  │
 * └──────────┴──────────────────────────────────────────────────────────────┘
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ──────────────────────────────────────────────
    // 공통 설정
    // ──────────────────────────────────────────────
    private Map<String, Object> baseProducerConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configs.put(ProducerConfig.RETRIES_CONFIG, 3);
        configs.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 20000);
        configs.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        return configs;
    }

    // ──────────────────────────────────────────────
    // acks=0 — Fire and Forget
    // ──────────────────────────────────────────────
    // 브로커가 받았는지 확인하지 않는다.
    // 네트워크 장애 시 메시지 유실됨.
    // 로그 수집, 클릭 이벤트 등 유실 허용 가능한 경우에만 사용.
    @Bean
    public KafkaTemplate<String, Object> acksZeroKafkaTemplate() {
        Map<String, Object> configs = baseProducerConfigs();
        configs.put(ProducerConfig.ACKS_CONFIG, "0");
        // acks=0에서는 idempotence 사용 불가
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        ProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(configs);
        return new KafkaTemplate<>(factory);
    }

    // ──────────────────────────────────────────────
    // acks=1 — Leader Only
    // ──────────────────────────────────────────────
    // Leader가 로컬 로그에 기록하면 응답.
    // Leader가 응답 후 Follower에 복제되기 전에 죽으면 유실.
    @Bean
    public KafkaTemplate<String, Object> acksOneKafkaTemplate() {
        Map<String, Object> configs = baseProducerConfigs();
        configs.put(ProducerConfig.ACKS_CONFIG, "1");
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        ProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(configs);
        return new KafkaTemplate<>(factory);
    }

    // ──────────────────────────────────────────────
    // acks=all — 모든 ISR 확인 (기본 — 가장 안전)
    // ──────────────────────────────────────────────
    // Leader + 모든 ISR Follower가 복제를 확인해야 응답.
    // min.insync.replicas=2와 조합하면 1대 죽어도 데이터 보장.
    // idempotence=true와 함께 사용 → 중복 전송도 방지.
    @Bean
    public KafkaTemplate<String, Object> acksAllKafkaTemplate() {
        Map<String, Object> configs = baseProducerConfigs();
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configs.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        ProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(configs);
        return new KafkaTemplate<>(factory);
    }
}
