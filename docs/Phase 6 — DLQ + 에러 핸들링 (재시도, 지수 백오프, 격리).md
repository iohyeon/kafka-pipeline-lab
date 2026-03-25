# Phase 6 — DLQ + 에러 핸들링 (재시도, 지수 백오프, 격리)

---

## 해결하는 문제

```
Phase 5까지의 한계:
  Consumer가 처리 실패하면 로그만 남기고 있었다.

  문제:
  - 실패 메시지가 어디에도 기록되지 않음
  - 재시도 없이 바로 포기
  - 실패 원인 추적 불가
  - 후속 복구 불가능

  DLQ가 해결하는 것:
  - 재시도 전략 (지수 백오프)
  - 재시도 모두 실패 시 DLQ 토픽으로 격리
  - 격리된 메시지의 원본 정보 보존 (원래 토픽, 파티션, 오프셋, 에러 메시지)
  - 모니터링 API로 실패 메시지 조회
```

---

## DLQ 처리 흐름

```
메시지 수신
     │
     ▼
멱등성 체크 (Phase 5)
     │
     ├── 이미 처리됨 → 스킵 + ACK
     │
     └── 미처리 → 비즈니스 로직 실행
            │
            ├── 성공 → event_handled 기록 + ACK
            │
            └── 실패 → 재시도 루프 시작
                  │
                  ├── attempt=1 → 실패 → 1초 대기 (백오프)
                  ├── attempt=2 → 실패 → 2초 대기 (백오프)
                  ├── attempt=3 → 실패
                  │
                  └── 3회 모두 실패 → DLQ 전송
                        │
                        ├── 원본 메시지 + 에러 헤더를 DLQ 토픽에 저장
                        ├── 원본 토픽에서 ACK (다음 메시지로 진행)
                        │
                        ▼
                  ┌──────────────────┐
                  │  pipeline.dlq-v1 │  ← 30일 보관
                  └──────────────────┘
                        │
                        ▼
                  DLQ Consumer가 읽어서
                  모니터링 + 알림 + 후처리
```

---

## 재시도 전략: 지수 백오프

```
backoffMs = 2^(attempt-1) × 1000ms

  attempt=1 → 2^0 × 1000 = 1초 대기
  attempt=2 → 2^1 × 1000 = 2초 대기
  attempt=3 → 2^2 × 1000 = 4초 대기
  총 대기: 7초

왜 지수 백오프인가?
  - 일시적 장애(DB 커넥션 풀 고갈, 네트워크 순단)는 잠시 기다리면 복구됨
  - 고정 간격(1초, 1초, 1초)보다 점점 길게 기다리면 복구 확률 높음
  - 장애 상태에서 빠른 재시도는 오히려 부하를 가중시킴
```

---

## DLQ 메시지 헤더 설계

```
DLQ 토픽에 전송되는 메시지:
  payload = 원본 메시지 (그대로)
  key = 원본 key (그대로)

  헤더 (추가):
  ┌──────────────────────┬─────────────────────────────────────────┐
  │ X-Original-Topic     │ 원래 토픽명 (inventory.deduct.event-v1) │
  │ X-Original-Partition │ 원래 파티션 번호 (0)                    │
  │ X-Original-Offset    │ 원래 오프셋 (33)                       │
  │ X-Error-Message      │ 실패 사유                               │
  │ X-Error-Timestamp    │ 실패 시각                               │
  │ X-Retry-Count        │ 재시도 횟수 (3)                         │
  └──────────────────────┴─────────────────────────────────────────┘

  → 원본 메시지 + 실패 맥락을 전부 보존
  → 후처리 시 "어디서, 언제, 왜 실패했는지" 추적 가능
```

---

## 실측 결과

### 실험 1: 정상 처리 (failMode=false)

```
발행: 3건 → inventory.deduct.event-v1
결과: 3건 모두 정상 처리, DLQ 0건

→ 정상 상황에서는 DLQ에 아무것도 안 감.
```

### 실험 2: 의도적 실패 → 3회 재시도 → DLQ

```
설정: failMode=true
발행: 2건 → inventory.deduct.event-v1

로그 (시간순):

  [메시지 1 — partition=0, offset=33, key=1]
  12:59:48.562  재시도 1/3 실패 — error=의도적 실패
  12:59:48.563  1000ms 후 재시도...
  12:59:49.566  재시도 2/3 실패 — error=의도적 실패
  12:59:49.566  2000ms 후 재시도...
  12:59:51.566  재시도 3/3 실패 — error=의도적 실패
  12:59:51.567  3회 재시도 모두 실패 → DLQ 전송
  12:59:51.681  [DLQ] 격리 완료 — dlqOffset=0

  [메시지 2 — partition=2, offset=34, key=2]
  (동시 진행 — 다른 파티션의 Consumer)
  12:59:48.562  재시도 1/3 실패
  12:59:49.565  재시도 2/3 실패
  12:59:51.566  재시도 3/3 실패
  12:59:51.697  [DLQ] 격리 완료 — dlqOffset=1

시간 분석:
  메시지당 총 소요: 약 3초 (재시도 1초+2초 대기)
  2건이 다른 파티션이라 병렬 처리됨 → 전체 3초
```

### DLQ 메시지 내용

```json
{
  "dlqCount": 2,
  "messages": [
    {
      "originalTopic": "inventory.deduct.event-v1",
      "originalPartition": "2",
      "originalOffset": "34",
      "retryCount": "3",
      "errorMessage": "의도적 실패 (failMode=true) — attempt=3",
      "errorTimestamp": "2026-03-25T12:59:51.567311",
      "key": "2",
      "payload": "{orderId:2, userId:1, totalAmount:10200, eventId:decb2ca2-...}"
    },
    {
      "originalTopic": "inventory.deduct.event-v1",
      "originalPartition": "0",
      "originalOffset": "33",
      "retryCount": "3",
      "errorMessage": "의도적 실패 (failMode=true) — attempt=3",
      "errorTimestamp": "2026-03-25T12:59:51.567311",
      "key": "1",
      "payload": "{orderId:1, userId:1, totalAmount:10100, eventId:11a4ce85-...}"
    }
  ]
}
```

---

## DLQ 이후 후처리 전략

```
DLQ에 메시지가 쌓이면?

  1. 모니터링 알림 (Slack/Teams)
     → DLQ Consumer가 메시지 수신 시 알림 전송
     → "inventory.deduct.event-v1에서 실패 메시지 2건 발생"

  2. 수동 확인
     → GET /api/experiment/dlq/messages로 실패 메시지 조회
     → 원본 토픽, 파티션, 오프셋, 에러 메시지 확인
     → 원인 파악 (DB 장애? 외부 API 장애? 코드 버그?)

  3. 재처리
     → 원인 해결 후 DLQ 메시지를 원본 토픽에 재발행
     → 또는 DLQ Consumer가 자동으로 원본 토픽에 재전송

  4. 폐기
     → 복구 불가한 메시지 (잘못된 데이터, 이미 보상 처리 완료)
     → DLQ retention(30일) 이후 자동 삭제
```

---

## 실험 API 정리

```bash
# 실패 모드 ON/OFF
curl -X POST http://localhost:8085/api/experiment/dlq/fail-mode/true
curl -X POST http://localhost:8085/api/experiment/dlq/fail-mode/false

# 메시지 발행
curl -X POST http://localhost:8085/api/experiment/dlq/publish/3

# DLQ 메시지 조회
curl http://localhost:8085/api/experiment/dlq/messages

# DLQ 토픽 직접 확인
docker exec kafka-1 kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic pipeline.dlq-v1 \
  --from-beginning

# 실험 가이드
curl http://localhost:8085/api/experiment/dlq/guide
```

---

## 프로젝트 구조 (Phase 6 추가분)

```
src/main/java/com/pipeline/
├── dlq/
│   ├── DlqPublisher.java              # DLQ 토픽으로 실패 메시지 전송 (헤더 포함)
│   └── DlqConsumer.java               # DLQ 모니터링 Consumer (메모리 보관 + API 조회)
├── consumer/
│   └── RetryableOrderConsumer.java    # 재시도 3회 + 지수 백오프 + DLQ 전송
└── api/
    └── DlqExperimentController.java   # failMode 제어 + DLQ 조회 API
```

---

## Phase 1~6 완성된 전체 파이프라인

```
┌──────────────────────────────────────────────────────────────────────┐
│  Phase 4: Outbox (안전한 발행)                                       │
│  @Transactional { order 저장 + outbox 저장 }                         │
│  → Relay (Polling 5초, 동기) → Kafka 발행                           │
│  → at-least-once 보장                                                │
├──────────────────────────────────────────────────────────────────────┤
│  Phase 2: Producer (acks=all + idempotence)                          │
│  → key 기반 파티션 라우팅 → 순서 보장                                │
├──────────────────────────────────────────────────────────────────────┤
│  Phase 1: Kafka Cluster (3 Broker, KRaft)                            │
│  → Replication=3, Min ISR=2 → 1대 죽어도 유실 없음                  │
├──────────────────────────────────────────────────────────────────────┤
│  Phase 3: Consumer (Manual ACK + Batch)                              │
│  → offset 수동 커밋 → 처리 완료 후에만 진행                         │
├──────────────────────────────────────────────────────────────────────┤
│  Phase 5: 멱등성 (event_handled + 같은 TX)                           │
│  → 중복 메시지 스킵 → 비즈니스 1번만 실행                           │
├──────────────────────────────────────────────────────────────────────┤
│  Phase 6: DLQ (3회 재시도 + 지수 백오프 + 격리)                      │
│  → 처리 실패 메시지를 DLQ에 보존 → 모니터링 + 후처리                │
└──────────────────────────────────────────────────────────────────────┘

결과:
  안전한 발행 (Outbox) +
  유실 없는 전송 (acks=all, Replication) +
  순서 보장 (key 라우팅) +
  중복 방지 (멱등성) +
  실패 복구 (DLQ) =
  실무 수준의 이벤트 파이프라인 완성
```

---

## 다음 단계: Phase 7 — 실전 선착순 쿠폰 발급

- couponId를 key로 → 순차 처리로 동시성 제어
- 수량 제한 (100장)
- 1만 요청 동시성 테스트
- Phase 1~6의 모든 기법을 하나의 시나리오에 통합
