# 종합 회고 — Phase 1~3 (인프라 → Producer → Consumer)

---

## Phase 1~3에서 한 것

```
Phase 1: 인프라 기반
  → 3-Broker KRaft 클러스터 + Kafka UI + MySQL + Prometheus + Grafana
  → Topic 4개 설계 (order/coupon/inventory/DLQ)
  → 토픽 네이밍 컨벤션, 파티션 수/복제본 수 결정

Phase 2: Producer 심화
  → acks=0/1/all 비교 실험 (단건 + 벌크 100/1000건)
  → idempotence=true 설정 (PID+Seq 중복 방지)
  → Partition Key 라우팅 검증 (같은 key → 같은 파티션 실측)

Phase 3: Consumer 심화
  → Manual ACK vs Auto Commit 차이
  → Batch Listener 구현
  → LAG 적체/복구 실험 (SlowConsumer)
  → Consumer Group 독립 소비 실측 확인
```

---

## 전체 메시지 흐름 — Phase 1~3 관통

```
                          ┌─── Phase 2 ──────────────────────────────────────────────┐
                          │                                                          │
                          │  ┌──────────┐     acks=all          ┌────────────────┐   │
  API 요청 ──→ Controller │──│ Producer │──→ idempotence=true ──│ RecordAccumul. │   │
  (curl)                  │  └──────────┘    key=orderId        │ (내부 버퍼)     │   │
                          │                                      └───────┬────────┘   │
                          │                                              │             │
                          └──────────────────────────────────────────────┼─────────────┘
                                                                         │
                                                                    Sender 스레드
                                                                    (배치 전송)
                                                                         │
                          ┌─── Phase 1 ──────────────────────────────────┼─────────────┐
                          │                                              │             │
                          │         ┌──────────────────────────┐         │             │
                          │         │  Kafka Cluster (3 Broker) │         │             │
                          │         │                          │         │             │
                          │         │  Topic: order.created... │◀────────┘             │
                          │         │  ┌────────┐ ┌────────┐   │                       │
                          │         │  │ P0     │ │ P1     │   │  Replication          │
                          │         │  │ L:B2   │ │ L:B3   │   │  Factor=3             │
                          │         │  │ ISR:3  │ │ ISR:3  │   │  Min ISR=2            │
                          │         │  └────┬───┘ └────┬───┘   │                       │
                          │         │  ┌────────┐      │       │                       │
                          │         │  │ P2     │      │       │                       │
                          │         │  │ L:B1   │      │       │                       │
                          │         │  └────┬───┘      │       │                       │
                          │         └───────┼──────────┼───────┘                       │
                          │                 │          │                                │
                          └─────────────────┼──────────┼────────────────────────────────┘
                                            │          │
                          ┌─── Phase 3 ─────┼──────────┼────────────────────────────────┐
                          │                 │          │                                │
                          │    Consumer Group A                Consumer Group B         │
                          │    (order-inventory-group)         (order-batch-group)      │
                          │    ┌─────────┐┌─────────┐          ┌─────────┐             │
                          │    │ C1 → P0 ││ C2 → P1 │          │ Batch   │             │
                          │    │ offset  ││ offset  │          │ C7,8,9  │             │
                          │    │ =1203   ││ =1095   │          │ 독립 소비│             │
                          │    └────┬────┘└────┬────┘          └────┬────┘             │
                          │         │          │                    │                   │
                          │         ▼          ▼                    ▼                   │
                          │    Manual ACK  Manual ACK         Batch ACK                │
                          │    (ack.acknowledge())            (전체 배치 후 1번)        │
                          │                                                            │
                          └────────────────────────────────────────────────────────────┘
```

---

## 삽질 & 교훈 타임라인

```
Phase 1:
  [문제] bitnami/kafka:3.7.0 이미지 없음
  [원인] bitnami → bitnamilegacy 레지스트리 이전
  [교훈] 기존에 동작하는 이미지를 먼저 확인하고 쓰자

  [문제] KRaft Cluster ID가 UUID 형식이어야 함
  [원인] 사람이 읽기 좋은 문자열은 base64 UUID가 아님
  [교훈] KRaft Cluster ID는 kafka-storage.sh random-uuid로 생성
         → docker compose down -v로 볼륨 초기화 후 재시작 필요

Phase 2:
  [발견] acks=all이 벌크에서 acks=0보다 빠름
  [원인] send()는 비동기 — 측정하는 건 "버퍼에 넣는 시간"
  [교훈] "측정하는 것이 무엇인지"를 정확히 알아야 함
         성능이 아닌 안전성으로 acks를 선택해야 한다

Phase 3:
  [문제] containerFactory 미지정 시 Spring 기본 Factory와 충돌
  [원인] KafkaConsumerConfig에서 별도 Factory를 만들면서 기본 설정이 겹김
  [교훈] @KafkaListener에 containerFactory를 명시적으로 지정하자
```

---

## 핵심 트레이드오프 매트릭스

```
┌──────────────────┬──────────────────┬──────────────────┬──────────────────┐
│                  │ 안전성 ↑          │ 처리량 ↑          │ 복잡도 ↓          │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ acks=all         │ ✅ 유실 방지      │ △ ISR 대기       │ △ 설정 추가      │
│ acks=0           │ ❌ 유실 가능      │ ✅ 최소 지연      │ ✅ 단순          │
│ Manual ACK       │ ✅ 재처리 가능    │ △ ACK 호출 비용  │ △ 코드 추가      │
│ Auto Commit      │ ❌ 유실 위험      │ ✅ 코드 단순      │ ✅ 설정만        │
│ Batch Listener   │ △ 부분 실패 처리  │ ✅ bulk 처리      │ △ 에러 핸들링    │
│ idempotence      │ ✅ 중복 방지      │ △ 약간의 오버헤드 │ △ 제약사항 있음  │
│ Replication=3    │ ✅ 1대 장애 허용  │ △ 디스크 3배     │ △ 설정 복잡      │
└──────────────────┴──────────────────┴──────────────────┴──────────────────┘

실무 기본 선택: acks=all + Manual ACK + idempotence=true + Replication=3
→ 안전성을 먼저 확보하고, 처리량이 부족하면 배치/파티션 확장으로 대응
```

---

## Phase 1~3에서 아직 해결 안 된 문제

```
1. DB와 Kafka 발행의 원자성
   → DB에 저장했는데 Kafka 발행이 실패하면?
   → 또는 Kafka 발행은 됐는데 DB가 롤백되면?
   → Phase 4 (Transactional Outbox)에서 해결

2. Consumer 쪽 중복 처리
   → at-least-once 보장이라 같은 메시지가 2번 올 수 있음
   → Phase 5 (멱등성 — event_handled 테이블)에서 해결

3. 처리 실패 메시지의 격리
   → 실패하면 로그만 남기고 있음
   → Phase 6 (DLQ)에서 해결

4. 실전 동시성 시나리오
   → couponId key로 순차 처리 + 수량 제한 + 동시 1만 요청
   → Phase 7에서 해결
```

---

## 현재 프로젝트 구조

```
kafka-pipeline-lab/
├── docker/
│   ├── kafka-cluster-compose.yml     # 3-Broker KRaft + Kafka UI + MySQL
│   ├── monitoring-compose.yml        # Prometheus + Grafana
│   └── grafana/
│       ├── prometheus.yml
│       └── provisioning/datasources/datasource.yml
├── src/main/java/com/pipeline/
│   ├── KafkaPipelineLabApplication.java
│   ├── api/
│   │   ├── KafkaTestController.java           # Phase 1 — 기본 테스트
│   │   ├── ProducerExperimentController.java  # Phase 2 — acks/key 실험
│   │   └── ConsumerExperimentController.java  # Phase 3 — LAG/지연 실험
│   ├── config/
│   │   ├── KafkaTopicConfig.java              # Phase 1 — 토픽 4개 설계
│   │   ├── KafkaProducerConfig.java           # Phase 2 — acks별 3개 Template
│   │   └── KafkaConsumerConfig.java           # Phase 3 — 3가지 Factory
│   ├── consumer/
│   │   ├── OrderEventConsumer.java            # Phase 1 — 단건 Manual ACK
│   │   ├── BatchOrderEventConsumer.java       # Phase 3 — 배치 Consumer
│   │   └── SlowConsumer.java                  # Phase 3 — LAG 실험용
│   ├── event/
│   │   └── OrderCreatedEvent.java             # Phase 1 — 이벤트 DTO
│   └── producer/
│       ├── OrderEventProducer.java            # Phase 1 → acks=all로 변경
│       ├── AcksComparisonProducer.java        # Phase 2 — acks 비교
│       └── KeyRoutingProducer.java            # Phase 2 — key 라우팅 검증
├── src/main/resources/
│   └── application.yml                        # Kafka + JPA + Actuator 설정
├── build.gradle.kts
├── settings.gradle.kts
└── .gitignore
```

---

## 다음: Phase 4 — Transactional Outbox

```
해결할 문제:
  @Transactional 안에서 DB 저장 + Kafka 발행을 동시에 해야 하는데,
  둘 중 하나만 성공하면 정합성이 깨진다.

  → Outbox 테이블에 이벤트를 DB와 같은 TX로 저장
  → 별도 릴레이(Polling)가 Outbox를 읽어서 Kafka로 발행
  → 발행 후 Outbox 상태를 PUBLISHED로 변경
```
