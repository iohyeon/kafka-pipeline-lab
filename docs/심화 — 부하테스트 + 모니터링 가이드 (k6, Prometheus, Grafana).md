# 심화 — 부하테스트 + 모니터링 가이드

## 개요

코드를 짜는 것과 운영하는 것은 다른 영역이다.
"이 시스템은 초당 몇 건을 처리할 수 있는가?", "트래픽이 10배 급증하면 어디가 먼저 터지는가?"
이 질문에 답하려면 **부하테스트 + 모니터링**이 필요하다.

이 프로젝트에서는 k6(부하 생성) + Prometheus(메트릭 수집) + Grafana(시각화)를 조합하여 Kafka 파이프라인의 성능을 실측하고 병목을 찾는다.

---

## 아키텍처

```
                    ┌──────────────────────────────────────────┐
                    │            Grafana (:3000)                │
                    │  ┌─────────────────────────────────────┐ │
                    │  │  Kafka Pipeline Performance Dashboard│ │
                    │  │  - HTTP TPS, Latency, Error Rate     │ │
                    │  │  - Producer/Consumer Rate             │ │
                    │  │  - Consumer Lag                       │ │
                    │  │  - Broker Messages In                 │ │
                    │  │  - JVM Heap, HikariCP, GC             │ │
                    │  └─────────────────────────────────────┘ │
                    └────────────────┬─────────────────────────┘
                                     │ PromQL Query
                    ┌────────────────▼─────────────────────────┐
                    │         Prometheus (:9090)                 │
                    │  Scrape 대상:                              │
                    │  - Spring Boot /actuator/prometheus (:8085)│
                    │  - Kafka Broker JMX Exporter (:17071~37071)│
                    └────────────────┬─────────────────────────┘
                                     │ 5초 간격 수집
          ┌──────────────────────────┼──────────────────────────┐
          │                          │                          │
┌─────────▼──────────┐  ┌───────────▼──────────┐  ┌───────────▼──────────┐
│  Spring Boot (:8085)│  │ Kafka Broker-1 (JMX) │  │ Kafka Broker-2/3 (JMX)│
│  - HTTP 메트릭      │  │ - Messages In/Out    │  │                       │
│  - Kafka Client     │  │ - Under-replicated   │  │                       │
│  - JVM/HikariCP     │  │ - Request Latency    │  │                       │
└─────────▲──────────┘  └──────────────────────┘  └───────────────────────┘
          │
┌─────────┴──────────┐
│     k6 Load Test    │
│  - Baseline (50 VU) │
│  - Concurrency (500)│
│  - Spike (200 VU)   │
└─────────────────────┘
```

---

## 실행 방법

### 1. 인프라 기동

```bash
# Kafka 3-Broker + MySQL + Debezium + Kafka UI
docker compose -f docker/kafka-cluster-compose.yml up -d

# Prometheus + Grafana
docker compose -f docker/monitoring-compose.yml up -d

# Spring Boot 앱
./gradlew bootRun
```

### 2. Grafana 대시보드 확인

- URL: http://localhost:3000
- 로그인: admin / admin
- 대시보드: "Kafka Pipeline Lab — Performance Dashboard" (자동 프로비저닝)

### 3. 부하테스트 실행

```bash
# 전체 실행 (약 9분)
bash load-test/run-all.sh

# 개별 실행
k6 run load-test/01-outbox-baseline.js    # Outbox 기본 부하 (3분 30초)
k6 run load-test/02-coupon-concurrency.js # 동시성 부하 (1분 10초)
k6 run load-test/03-spike-test.js         # 스파이크 테스트 (4분 30초)
```

---

## 부하 시나리오 3개

### 시나리오 1: Outbox Baseline (Steady Load)

| 항목 | 설정 |
|------|------|
| VUs | 10 → 30 → 50 (점진 증가) |
| 시간 | 3분 30초 |
| 요청 | POST /api/experiment/outbox/order |
| 목적 | 기본 TPS, Latency, Error Rate 측정 |
| 임계값 | p95 < 500ms, 에러율 < 1% |

**Grafana에서 볼 것**: HTTP Request Rate + Consumer Lag 상관관계
→ TPS가 올라가면 Consumer Lag도 비례하는가? 아니면 Consumer가 충분히 빠른가?

### 시나리오 2: Coupon Concurrency (Stress)

| 항목 | 설정 |
|------|------|
| VUs | 100 → 300 → 500 (급증) |
| 시간 | 1분 10초 |
| 요청 | POST /api/experiment/outbox/order |
| 목적 | 고부하 시 DB 커넥션 풀 + Kafka Producer 병목 식별 |
| 임계값 | p95 < 1000ms, 에러율 < 5% |

**Grafana에서 볼 것**: HikariCP Active Connections + Producer Latency
→ 커넥션 풀이 max에 도달하면? 응답 시간이 급등하는 시점은?

### 시나리오 3: Spike Test

| 항목 | 설정 |
|------|------|
| VUs | 20 → **200** (2초 만에) → 20 → **200** → 0 |
| 시간 | 4분 30초 |
| 요청 | POST /api/experiment/outbox/order |
| 목적 | 급증 시 시스템 반응 + 복구 시간 측정 |
| 임계값 | p95 < 2000ms, 에러율 < 10% |

**Grafana에서 볼 것**: 스파이크 시점의 Consumer Lag 급증 → 복구까지 걸리는 시간
→ 이것이 실무에서 "타임세일 후 시스템이 정상으로 돌아오는 데 걸리는 시간"

---

## Grafana 대시보드 패널 설명

### Spring Boot Application
| 패널 | 메트릭 | 의미 |
|------|--------|------|
| HTTP Request Rate | http_server_requests_seconds_count | 초당 API 처리량 (TPS) |
| HTTP Response Time | http_server_requests_seconds_bucket | p95, p99, avg 응답 시간 |
| HTTP Error Rate | status=5xx / total | 서버 에러 비율 |

### Kafka Producer
| 패널 | 메트릭 | 의미 |
|------|--------|------|
| Record Send Rate | kafka_producer_record_send_total | 초당 Kafka로 보낸 레코드 수 |
| Request Latency | kafka_producer_request_latency_avg | send()→ack 평균 시간 |
| Errors / Retries | kafka_producer_record_error_total | Producer 에러 + 재시도 |

### Kafka Consumer
| 패널 | 메트릭 | 의미 |
|------|--------|------|
| Records Consumed | kafka_consumer_fetch_manager_records_consumed_total | 초당 처리한 레코드 |
| **Consumer Lag** | kafka_consumer_fetch_manager_records_lag | **핵심** — 밀린 레코드 수 |
| Commit Rate | kafka_consumer_coordinator_commit_total | 오프셋 커밋 빈도 |

### Kafka Broker (JMX)
| 패널 | 메트릭 | 의미 |
|------|--------|------|
| Messages In | kafka_server_broker_topic_metrics_messages_in_total | 브로커 수신 메시지 |
| Under-Replicated | kafka_server_under_replicated_partitions | 복제 지연 파티션 (0 = 정상) |
| Produce p99 | kafka_network_request_total_time_ms | 브로커의 Produce 처리 시간 |

### JVM
| 패널 | 메트릭 | 의미 |
|------|--------|------|
| Heap Usage | jvm_memory_used_bytes | 힙 메모리 사용량 (GC 패턴 관찰) |
| HikariCP | hikaricp_connections_active | DB 커넥션 풀 사용량 |
| GC Pause | jvm_gc_pause_seconds_sum | GC 정지 시간 (stop-the-world) |

---

## 병목 식별 가이드

### "어디가 먼저 터지는가?"

| 증상 | 원인 | Grafana에서 확인 | 조치 |
|------|------|-----------------|------|
| 응답 시간 급증 | DB 커넥션 풀 고갈 | HikariCP active = max | spring.datasource.hikari.maximum-pool-size 증가 |
| 응답 시간 급증 | Kafka Producer 대기 | Producer Latency 급등 | batch.size, linger.ms 튜닝 |
| Consumer Lag 급증 | Consumer 처리 속도 부족 | Lag > 0 지속 | concurrency 증가 or Consumer 로직 최적화 |
| 5xx 에러 증가 | 스레드 풀 고갈 | Error Rate > 0 | Tomcat 스레드 풀 or async 처리 |
| Broker Latency 증가 | 브로커 과부하 | Produce p99 급등 | 파티션 수 증가 or 브로커 스케일 아웃 |
| Under-replicated > 0 | 브로커 장애/네트워크 | Under-replicated stat | 브로커 상태 확인, 네트워크 진단 |

---

## 관련 파일

| 파일 | 설명 |
|------|------|
| load-test/01-outbox-baseline.js | 시나리오 1: Outbox 기본 부하 |
| load-test/02-coupon-concurrency.js | 시나리오 2: 동시성 부하 |
| load-test/03-spike-test.js | 시나리오 3: 스파이크 |
| load-test/run-all.sh | 전체 실행 스크립트 |
| docker/jmx-exporter/ | Kafka JMX Exporter 설정 + JAR |
| docker/grafana/provisioning/dashboards/ | Grafana 대시보드 JSON |
| docker/grafana/prometheus.yml | Prometheus 수집 대상 설정 |
