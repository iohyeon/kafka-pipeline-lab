# 종합 회고 — Phase 1~7 전체 (Kafka 파이프라인 완성)

---

## Phase 1~7에서 한 것

```
Phase 1: 멀티 브로커 KRaft 클러스터 구축
  → 3-Broker + Kafka UI + MySQL + Prometheus + Grafana
  → Topic 4개 설계, 네이밍 컨벤션, Replication/ISR 설정

Phase 2: Producer 심화
  → acks=0/1/all 비교 실험 (단건 + 벌크 100/1000건)
  → idempotence=true (PID+Seq 중복 방지)
  → Partition Key 라우팅 검증 (같은 key → 같은 파티션 실측)

Phase 3: Consumer 심화
  → Manual ACK vs Auto Commit
  → Batch Listener
  → LAG 적체/복구 실험 (SlowConsumer)
  → Consumer Group 독립 소비 실측

Phase 4: Transactional Outbox
  → DB TX + Outbox 저장 (같은 TX)
  → Polling Relay (동기 .get())
  → 비동기 콜백 + @Transactional 충돌 버그 발견 → 수정

Phase 5: 멱등성 처리
  → event_handled 테이블 (UNIQUE eventId)
  → 비즈니스 + 멱등 기록을 같은 TX
  → 2건/10건 중복 발행 → 1건만 처리 실측

Phase 6: DLQ + 에러 핸들링
  → 3회 재시도 + 지수 백오프 (1초→2초→4초)
  → 실패 메시지를 DLQ 토픽으로 격리 (헤더에 원본 정보 보존)
  → DLQ Consumer 모니터링

Phase 7: 실전 선착순 쿠폰 발급
  → couponId를 key → 같은 파티션 → 순차 처리 → 동시성 제어
  → 200명/1000명 동시 요청 → 100장 정확 발급 → 초과 0건
  → Phase 1~6 전부 통합
```

---

## 전체 메시지 흐름 — Phase 1~7 관통

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│  [사용자 요청]  200~1000명 동시                                          │
│       │                                                                  │
│       ▼                                                                  │
│  Phase 4: Outbox                                                         │
│  @Transactional { 비즈니스 저장 + outbox 저장 }                          │
│  Relay (5초 Polling, 동기 .get()) → at-least-once                       │
│       │                                                                  │
│       ▼                                                                  │
│  Phase 2: Producer                                                       │
│  key=couponId/orderId → acks=all + idempotence=true                     │
│  RecordAccumulator → Sender 스레드 → 배치 전송                          │
│       │                                                                  │
│       ▼                                                                  │
│  Phase 1: Kafka Cluster                                                  │
│  3 Broker (KRaft) │ Replication=3 │ Min ISR=2                           │
│  key 해시 → 파티션 라우팅 → Leader → Follower 복제                      │
│       │                                                                  │
│       ▼                                                                  │
│  Phase 3: Consumer                                                       │
│  Manual ACK │ concurrency=3 │ 1 Partition : 1 Consumer                  │
│  poll() → 단건/배치 수신                                                │
│       │                                                                  │
│       ▼                                                                  │
│  Phase 5: 멱등성                                                         │
│  event_handled 조회 → 중복이면 스킵 + ACK                               │
│  신규면 → @Transactional { 비즈니스 + event_handled INSERT }            │
│       │                                                                  │
│       ├── 성공 → ACK (offset 커밋)                                      │
│       │                                                                  │
│       └── 실패 → Phase 6: 재시도 3회 (지수 백오프)                      │
│              │                                                           │
│              ├── 재시도 성공 → ACK                                       │
│              │                                                           │
│              └── 3회 모두 실패 → DLQ 전송 (헤더에 원본 정보) → ACK       │
│                                    │                                     │
│                                    ▼                                     │
│                              pipeline.dlq-v1                             │
│                              DLQ Consumer → 모니터링/알림/후처리          │
│                                                                          │
│  Phase 7: 결과                                                           │
│  200명 요청 → 100명 발급 → 100명 거부 → 초과 0건 → 정합성 ✅             │
│  1000명 요청 → 100명 발급 → 900명 거부 → 초과 0건 → 정합성 ✅            │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 삽질 & 교훈 타임라인

```
Phase 1:
  [문제] bitnami/kafka:3.7.0 이미지 not found
  [해결] bitnamilegacy/kafka:3.5.1로 변경
  [교훈] Docker 이미지 레지스트리는 변경될 수 있다. 기존에 동작하는 이미지 먼저 확인.

  [문제] KRaft Cluster ID가 base64 UUID여야 함
  [해결] "MkU3OEVBNTcwNTJENDM2Qk"으로 변경 + docker compose down -v
  [교훈] KRaft Cluster ID는 kafka-storage.sh random-uuid로 생성.

Phase 2:
  [발견] acks=all이 벌크에서 acks=0보다 빠름
  [원인] send()는 비동기 — 측정하는 건 "버퍼에 넣는 시간"
  [교훈] "측정하는 것이 무엇인지"를 정확히 알아야 함.
         성능이 아닌 안전성으로 acks를 선택.

Phase 3:
  [문제] containerFactory 미지정 시 Spring 기본 Factory와 충돌
  [해결] "manualAckFactory"로 명시적 지정
  [교훈] @KafkaListener에 containerFactory를 항상 명시.

Phase 4:
  [버그] 비동기 whenComplete + @Transactional → markPublished() DB 미반영 → 무한 반복 발행
  [원인] @Transactional TX 경계 = 메서드 리턴 시점. 콜백은 TX 밖에서 실행.
  [해결] 동기 .get(10s)로 변경
  [교훈] 비동기 콜백과 @Transactional은 항상 충돌 위험.
         Outbox Relay는 안전성이 목적 → 동기가 맞다.

  [논의] "비동기로 해야 하지 않아?"
  [결론] 멘토링에서 비동기 Polling은 언급 없음.
         Polling(동기) → CDC가 실무 전환 경로.
         비동기 Polling은 복잡도 대비 이점이 CDC보다 못함.

Phase 5:
  [설계] Redis vs DB 멱등성 저장소
  [결론] DB event_handled (케브님 권장)
         Redis SET NX는 비즈니스 실패 시 재처리 불가 문제.
         DB는 같은 TX로 묶으면 롤백 시 재처리 가능.

Phase 6:
  [설계] 재시도 전략
  [결론] 지수 백오프 (2^n × 1000ms). 고정 간격보다 복구 확률 높음.
         3회 실패 → DLQ 격리. DLQ에 원본 정보 헤더 보존.

Phase 7:
  [문제] 한글 쿠폰명이 URL 인코딩 문제 발생
  [해결] 영문명으로 변경 (API RequestParam 인코딩 이슈)
  [관찰] 테스트 2가 테스트 1보다 건당 느림 (23.7ms → 50.2ms)
         event_handled 테이블 크기 증가로 SELECT 비용 증가.
         운영에서는 정기 삭제로 대응.
```

---

## 핵심 트레이드오프 매트릭스 (최종)

```
┌──────────────────────┬──────────────────┬──────────────────┬──────────────────┐
│                      │ 안전성 ↑          │ 처리량 ↑          │ 복잡도 ↓          │
├──────────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ 3 Broker + Rep=3     │ ✅ 1대 장애 허용  │ △ 디스크 3배     │ △ 설정 복잡      │
│ acks=all             │ ✅ 유실 방지      │ △ ISR 대기       │ ✅ 설정만        │
│ Manual ACK           │ ✅ 재처리 가능    │ △ ACK 호출 비용  │ △ 코드 추가      │
│ Outbox (동기 Polling)│ ✅ DB-Kafka 원자성│ △ Polling 지연   │ ✅ 단순          │
│ event_handled (DB)   │ ✅ TX 롤백 가능   │ △ SELECT 비용    │ ✅ 단순          │
│ 3회 재시도 + 백오프   │ ✅ 일시 장애 복구 │ △ 지연 (7초)     │ △ 재시도 로직    │
│ DLQ                  │ ✅ 메시지 보존    │ ✅ 빠른 실패     │ △ DLQ Consumer   │
│ couponId key         │ ✅ 초과 발급 방지 │ △ 단일 Consumer  │ ✅ Lock 불필요   │
│ idempotence          │ ✅ Producer 중복  │ △ 약간의 오버헤드│ ✅ 설정만        │
└──────────────────────┴──────────────────┴──────────────────┴──────────────────┘

실무 기본 선택:
  acks=all + Manual ACK + Outbox(동기) + event_handled(DB) + DLQ + couponId key
  = 안전성을 먼저 확보하고, 처리량이 부족하면 배치/파티션 확장 또는 CDC로 대응
```

---

## 성능 실측 요약

```
┌──────────────────────┬──────────────────────────────────────────────┐
│ 항목                  │ 실측값                                       │
├──────────────────────┼──────────────────────────────────────────────┤
│ Producer 발행         │ 200건/247ms = 810건/초                       │
│                      │ 1000건/65ms = 15,384건/초                    │
├──────────────────────┼──────────────────────────────────────────────┤
│ Consumer 처리 (발급)  │ 건당 ~23ms (테스트 1) ~ 50ms (테스트 2)      │
│ Consumer 처리 (거부)  │ 건당 ~10ms                                   │
├──────────────────────┼──────────────────────────────────────────────┤
│ 멱등성 오버헤드       │ 건당 ~22ms (SELECT + INSERT + TX)            │
│ 멱등성 중복 스킵      │ 건당 ~4ms (SELECT만)                         │
├──────────────────────┼──────────────────────────────────────────────┤
│ Outbox Relay          │ PENDING → PUBLISHED: ~5초 (Polling 간격)    │
│                      │ 20건 배치: ~10초 이내 전부 PUBLISHED          │
├──────────────────────┼──────────────────────────────────────────────┤
│ DLQ 재시도            │ 3회 재시도 총 대기: 7초 (1+2+4초 백오프)     │
│                      │ DLQ 전송: ~114ms                             │
├──────────────────────┼──────────────────────────────────────────────┤
│ LAG 복구              │ delay=3초 → LAG 10 → delay 해제 → 15초 후 0 │
├──────────────────────┼──────────────────────────────────────────────┤
│ Key 라우팅            │ 같은 key → 100% 같은 파티션 (6개 key 검증)   │
│                      │ 3개 파티션 균등 분배                          │
└──────────────────────┴──────────────────────────────────────────────┘
```

---

## 멘토링 인사이트와 구현의 대조

```
┌──────────────────────┬────────────────────────┬──────────────────────┐
│ 케브님 권장            │ 우리 구현               │ 상태                 │
├──────────────────────┼────────────────────────┼──────────────────────┤
│ Outbox = 원자성       │ 동기 Polling            │ ✅ 일치              │
│ Polling → CDC 전환    │ Polling 구현            │ ✅ 정확한 위치       │
│ 멱등성은 DB           │ event_handled + TX      │ ✅ 일치              │
│ 유실 치명도로 선택적   │ order/coupon만 적용     │ ✅ 일치              │
│ 점진적 고도화          │ Phase 1→7 순서          │ ✅ 일치              │
│ couponId가 key        │ couponId key 사용       │ ✅ 일치              │
│ Command도 Outbox      │ 쿠폰 발급도 Outbox 가능 │ ✅ 이해              │
│ at-least-once + 멱등  │ Outbox + event_handled  │ ✅ 구현 완료         │
└──────────────────────┴────────────────────────┴──────────────────────┘
```

---

## 생성된 문서 전체 목록

```
기술적인 개념/
├── Phase 1 — 멀티 브로커 KRaft 클러스터 구축.md
├── Phase 1 히스토리 — 클러스터 구축 과정 기록.md
├── Phase 2 — Producer 심화 (acks, idempotence, key 라우팅).md
├── Phase 2 히스토리 — Producer 심화 실험 기록.md
├── Phase 3 — Consumer 심화 (리밸런싱, Manual ACK, Batch, LAG).md
├── Phase 3 히스토리 — Consumer 심화 실험 기록.md
├── Phase 4 — Transactional Outbox 패턴 구현.md
├── Phase 5 — 멱등성 처리 (event_handled + 같은 TX).md
├── Phase 5 실험 기록 — 멱등성 중복 감지 실측 상세.md
├── Phase 6 — DLQ + 에러 핸들링 (재시도, 지수 백오프, 격리).md
├── Phase 6 실험 기록 — DLQ 재시도와 격리 실측 상세.md
├── Phase 7 — 실전 선착순 쿠폰 발급 (동시성 테스트 + 전체 통합).md
├── Phase 7 실험 기록 — 선착순 쿠폰 동시성 테스트 실측 상세.md
├── Phase 7 실험 로그 — 선착순 쿠폰 전체 로그 + DB 상태 + Kafka 상태.md
├── Kafka 토픽 구조 설계 — kafka-pipeline-lab.md
├── 실행 가이드 — kafka-pipeline-lab 전체 명령어.md
├── Outbox Relay의 비동기 vs 동기 — @Transactional 경계 문제 심화.md
├── Outbox Relay 동기 vs 비동기 vs CDC — 실무 판단 기준.md
├── Outbox Relay 최종 트레이드오프 — 동기 Polling이 맞는 이유.md
├── 멘토링 인사이트 — Outbox 원자성, 팀별 질문과 케브님 답변 종합.md
├── 종합 회고 — Phase 1~3 (인프라 → Producer → Consumer).md
└── 종합 회고 — Phase 1~7 전체 (Kafka 파이프라인 완성).md  ← 이 문서

총 22개 문서
```

---

## 프로젝트 최종 구조

```
kafka-pipeline-lab/
├── docker/
│   ├── kafka-cluster-compose.yml       # 3-Broker KRaft + Kafka UI + MySQL
│   ├── monitoring-compose.yml          # Prometheus + Grafana
│   └── grafana/
│       ├── prometheus.yml
│       └── provisioning/datasources/datasource.yml
├── src/main/java/com/pipeline/
│   ├── KafkaPipelineLabApplication.java  (@EnableScheduling)
│   ├── api/
│   │   ├── KafkaTestController.java              # Phase 1
│   │   ├── ProducerExperimentController.java     # Phase 2
│   │   ├── ConsumerExperimentController.java     # Phase 3
│   │   ├── OutboxExperimentController.java       # Phase 4
│   │   ├── IdempotencyExperimentController.java  # Phase 5
│   │   ├── DlqExperimentController.java          # Phase 6
│   │   └── CouponExperimentController.java       # Phase 7
│   ├── config/
│   │   ├── KafkaTopicConfig.java           # 토픽 4개 설계
│   │   ├── KafkaProducerConfig.java        # acks별 3개 Template
│   │   └── KafkaConsumerConfig.java        # 3가지 Factory
│   ├── consumer/
│   │   ├── OrderEventConsumer.java         # 멱등성 적용
│   │   ├── BatchOrderEventConsumer.java    # 배치 Consumer
│   │   ├── SlowConsumer.java               # LAG 실험용
│   │   └── RetryableOrderConsumer.java     # 재시도 + DLQ
│   ├── coupon/
│   │   ├── CouponEvent.java               # 이벤트
│   │   ├── CouponStock.java               # 재고 Entity
│   │   ├── CouponIssuedLog.java           # 발급 이력
│   │   ├── CouponStockRepository.java
│   │   ├── CouponIssuedLogRepository.java
│   │   ├── CouponIssueService.java        # 발급 (재고+이력+멱등 같은TX)
│   │   └── CouponIssueConsumer.java       # Consumer (전체 통합)
│   ├── dlq/
│   │   ├── DlqPublisher.java              # DLQ 전송 (헤더 포함)
│   │   └── DlqConsumer.java               # DLQ 모니터링
│   ├── event/
│   │   └── OrderCreatedEvent.java
│   ├── idempotency/
│   │   ├── EventHandled.java              # 멱등성 Entity
│   │   ├── EventHandledRepository.java
│   │   └── IdempotencyService.java        # 중복 체크 + 기록
│   ├── order/
│   │   └── OrderService.java              # Outbox 적용
│   ├── outbox/
│   │   ├── OutboxEvent.java               # Outbox Entity
│   │   ├── OutboxStatus.java              # PENDING/PUBLISHED/FAILED
│   │   ├── OutboxEventRepository.java
│   │   ├── OutboxEventService.java        # TX 안에서 저장
│   │   └── OutboxRelayService.java        # Polling Relay (동기)
│   └── producer/
│       ├── OrderEventProducer.java        # acks=all
│       ├── AcksComparisonProducer.java    # acks 비교
│       └── KeyRoutingProducer.java        # key 라우팅 검증
├── src/main/resources/
│   └── application.yml
├── build.gradle.kts
├── settings.gradle.kts
└── .gitignore
```

---

## 이 프로젝트에서 배운 것

```
1. Kafka는 "파티션 내 순서 보장 + 파티션 간 병렬 처리"로
   처리량과 동시성 제어를 동시에 잡는다.
   → Phase 7에서 couponId key로 Lock 없이 동시성 제어를 실증.

2. Outbox 패턴은 "DB-Kafka 원자성"을 위한 것이지 "빠른 발행"이 아니다.
   → 동기 Polling이 맞고, 빨라야 하면 CDC로 전환.
   → 비동기 Polling은 실무에서 안 쓰는 어정쩡한 위치.

3. 비동기 콜백과 @Transactional은 항상 충돌 위험이 있다.
   → TX 경계 = 메서드 리턴 시점. 콜백은 TX 밖에서 실행.
   → Phase 4에서 직접 버그를 만들고 원인을 파악해서 체득.

4. 멱등성은 Redis가 아닌 DB가 안전하다.
   → 비즈니스 TX와 같이 묶어야 롤백 시 재처리 가능.
   → Redis SET NX는 비즈니스 실패 시 영원히 재처리 불가.

5. "측정하는 것이 무엇인지"를 알아야 한다.
   → acks=all이 벌크에서 빠른 건 send()가 비동기라 버퍼에 넣는 시간만 측정했기 때문.
   → 실제 브로커 왕복 시간은 콜백에서만 알 수 있다.

6. 점진적 고도화가 실무 경로다.
   → 동기 → @Async → Outbox(Polling) → CDC
   → 처음부터 과하게 하지 않고, 비용이 커졌을 때 전환.
```
