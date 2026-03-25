# Phase 6 실험 기록 — DLQ 재시도와 격리 실측 상세

---

## 실험 환경

```
인프라: 3-Broker KRaft 클러스터 (kafka-1, kafka-2, kafka-3)
앱: kafka-pipeline-lab (port 8085)
토픽: inventory.deduct.event-v1 (partition 3, replicas 3)
DLQ 토픽: pipeline.dlq-v1 (partition 1, replicas 3, retention 30일)
Consumer Group: inventory-retry-group (concurrency=3, Manual ACK)
재시도: 최대 3회, 지수 백오프 (1초→2초→4초)
failMode: API로 동적 ON/OFF 가능
```

---

## 실험 1: 정상 처리 (failMode=false)

### 실행

```bash
# 1. 실패 모드 OFF
curl -s -X POST http://localhost:8085/api/experiment/dlq/fail-mode/false

# 2. 3건 발행
curl -s -X POST http://localhost:8085/api/experiment/dlq/publish/3

# 3. DLQ 확인
curl -s http://localhost:8085/api/experiment/dlq/messages
```

### 응답

```json
// fail-mode 응답
{
    "failMode": false,
    "설명": "정상 모드로 복귀"
}

// publish 응답
{
    "published": 3,
    "failMode": false
}

// DLQ 메시지 확인
{
    "dlqCount": 0,
    "messages": []
}
```

### 결과

```
┌──────────┬──────────┬───────────┬──────────────┬────────────┐
│ 메시지    │ 파티션    │ 오프셋    │ 결과          │ DLQ       │
├──────────┼──────────┼───────────┼──────────────┼────────────┤
│ orderId=1│ -        │ -         │ ✅ 정상 처리  │ ❌ 안 감   │
│ orderId=2│ -        │ -         │ ✅ 정상 처리  │ ❌ 안 감   │
│ orderId=3│ -        │ -         │ ✅ 정상 처리  │ ❌ 안 감   │
└──────────┴──────────┴───────────┴──────────────┴────────────┘

→ 정상 상황에서는 DLQ에 아무것도 안 감 ✅
→ 재시도 로직이 성공 시에는 비용 없이 통과
```

---

## 실험 2: 의도적 실패 → 3회 재시도 → DLQ 격리

### 실행

```bash
# 1. 실패 모드 ON
curl -s -X POST http://localhost:8085/api/experiment/dlq/fail-mode/true

# 2. 2건 발행
curl -s -X POST http://localhost:8085/api/experiment/dlq/publish/2

# 3. 20초 대기 (재시도 대기 포함)

# 4. DLQ 확인
curl -s http://localhost:8085/api/experiment/dlq/messages
```

### 응답

```json
// fail-mode 응답
{
    "failMode": true,
    "설명": "Consumer가 모든 메시지를 의도적으로 실패 → 3회 재시도 후 DLQ로 전송"
}

// publish 응답
{
    "published": 2,
    "failMode": true
}
```

### Consumer 로그 — 전체 시간순 (2건 병렬 처리)

```
[메시지 1 — partition=0, offset=33, key=1, eventId=11a4ce85-...]

12:59:48.555  [RetryConsumer] 수신 — partition=0, offset=33, eventId=11a4ce85-...
                │
                ▼  isAlreadyHandled → false (처음 봄)
                │
12:59:48.562  [RetryConsumer] 처리 중 — orderId=1, attempt=1
12:59:48.563  [RetryConsumer] 재시도 1/3 실패 — error=의도적 실패 (failMode=true) — attempt=1
12:59:48.563  [RetryConsumer] 1000ms 후 재시도...
                │
                ▼  Thread.sleep(1000)  ← 지수 백오프 2^0 × 1000 = 1초
                │
12:59:49.566  [RetryConsumer] 재시도 2/3 실패 — error=의도적 실패 (failMode=true) — attempt=2
12:59:49.566  [RetryConsumer] 2000ms 후 재시도...
                │
                ▼  Thread.sleep(2000)  ← 지수 백오프 2^1 × 1000 = 2초
                │
12:59:51.566  [RetryConsumer] 재시도 3/3 실패 — error=의도적 실패 (failMode=true) — attempt=3
                │
                ▼  MAX_RETRY(3) 도달 → DLQ 전송 결정
                │
12:59:51.567  [RetryConsumer] 3회 재시도 모두 실패 → DLQ 전송 — eventId=11a4ce85-...
                │
                ▼  DlqPublisher.sendToDlq() (동기 .get())
                │
12:59:51.681  [DLQ] 메시지 격리 완료
              — dlqPartition=0, dlqOffset=0
              — originalTopic=inventory.deduct.event-v1
              — originalPartition=0, originalOffset=33
              — error=의도적 실패 (failMode=true) — attempt=3
                │
                ▼  원본 토픽에서 ACK (다음 메시지로 진행)


[메시지 2 — partition=2, offset=34, key=2, eventId=decb2ca2-...]
(다른 파티션이므로 메시지 1과 병렬로 처리)

12:59:48.555  [RetryConsumer] 수신 — partition=2, offset=34, eventId=decb2ca2-...
12:59:48.562  재시도 1/3 실패
12:59:48.563  1000ms 후 재시도...
12:59:49.565  재시도 2/3 실패
12:59:49.565  2000ms 후 재시도...
12:59:51.566  재시도 3/3 실패
12:59:51.567  3회 재시도 모두 실패 → DLQ 전송
12:59:51.697  [DLQ] 메시지 격리 완료
              — dlqPartition=0, dlqOffset=1
              — originalTopic=inventory.deduct.event-v1
              — originalPartition=2, originalOffset=34
```

### 시간 분석

```
메시지 1 (partition=0):
  수신:        12:59:48.555
  재시도 1 실패: 12:59:48.563  (수신 후 8ms)
  백오프 1초:   12:59:48.563 ~ 12:59:49.566
  재시도 2 실패: 12:59:49.566  (1003ms 후)
  백오프 2초:   12:59:49.566 ~ 12:59:51.566
  재시도 3 실패: 12:59:51.566  (2000ms 후)
  DLQ 전송:    12:59:51.567 → 12:59:51.681  (114ms)

  총 소요: 12:59:48.555 ~ 12:59:51.681 = 3.126초

  내역:
  ┌───────────────┬──────────┐
  │ 단계           │ 시간     │
  ├───────────────┼──────────┤
  │ 수신 → 1차 실패│ 8ms     │
  │ 백오프 1       │ 1,003ms │
  │ 2차 실패       │ <1ms    │
  │ 백오프 2       │ 2,000ms │
  │ 3차 실패       │ <1ms    │
  │ DLQ 전송       │ 114ms   │
  │ 총 소요        │ 3,126ms │
  └───────────────┴──────────┘

메시지 2 (partition=2):
  동시 처리 → 총 소요: 3.142초 (메시지 1과 거의 동일)

→ 2건이 다른 파티션이라 병렬 처리됨
→ 전체 소요 시간 ≈ 3초 (7초가 아님!)
```

### DLQ 메시지 API 응답

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
            "payload": "{\"orderId\":2,\"userId\":1,\"totalAmount\":10200,\"eventId\":\"decb2ca2-c2ab-4169-bee5-59aa901dd823\",\"occurredAt\":[2026,3,25,12,59,48,536999000]}"
        },
        {
            "originalTopic": "inventory.deduct.event-v1",
            "originalPartition": "0",
            "originalOffset": "33",
            "retryCount": "3",
            "errorMessage": "의도적 실패 (failMode=true) — attempt=3",
            "errorTimestamp": "2026-03-25T12:59:51.567311",
            "key": "1",
            "payload": "{\"orderId\":1,\"userId\":1,\"totalAmount\":10100,\"eventId\":\"11a4ce85-06ca-42bf-ab00-b42bbc230133\",\"occurredAt\":[2026,3,25,12,59,48,536414000]}"
        }
    ]
}
```

### DLQ 메시지 헤더 상세

```
메시지 1 (dlqOffset=0):
  ┌──────────────────────┬──────────────────────────────────────────────┐
  │ 헤더                  │ 값                                          │
  ├──────────────────────┼──────────────────────────────────────────────┤
  │ X-Original-Topic     │ inventory.deduct.event-v1                    │
  │ X-Original-Partition │ 0                                            │
  │ X-Original-Offset    │ 33                                           │
  │ X-Error-Message      │ 의도적 실패 (failMode=true) — attempt=3     │
  │ X-Error-Timestamp    │ 2026-03-25T12:59:51.567311                   │
  │ X-Retry-Count        │ 3                                            │
  └──────────────────────┴──────────────────────────────────────────────┘

메시지 2 (dlqOffset=1):
  ┌──────────────────────┬──────────────────────────────────────────────┐
  │ X-Original-Topic     │ inventory.deduct.event-v1                    │
  │ X-Original-Partition │ 2                                            │
  │ X-Original-Offset    │ 34                                           │
  │ X-Error-Message      │ 의도적 실패 (failMode=true) — attempt=3     │
  │ X-Error-Timestamp    │ 2026-03-25T12:59:51.567311                   │
  │ X-Retry-Count        │ 3                                            │
  └──────────────────────┴──────────────────────────────────────────────┘

→ 원본 토픽/파티션/오프셋이 전부 보존됨
→ 후처리 시 "어디서 온 메시지가 왜 실패했는지" 완전 추적 가능
```

---

## 재시도 시퀀스 다이어그램

```
Consumer                        비즈니스 로직              DLQ Publisher           Kafka (DLQ)
─────────                      ──────────────             ──────────────          ────────────
  │                                 │                          │                       │
  ├── poll() record(offset=33) ────▶│                          │                       │
  │                                 │                          │                       │
  │   attempt=1                     │                          │                       │
  ├─────────────── processWithRetry ▶│                          │                       │
  │                                 ├── 실패! RuntimeException │                       │
  │◀──────────────────── catch ─────┤                          │                       │
  │                                 │                          │                       │
  │   sleep(1000ms)  ← 백오프 1초   │                          │                       │
  │                                 │                          │                       │
  │   attempt=2                     │                          │                       │
  ├─────────────── processWithRetry ▶│                          │                       │
  │                                 ├── 실패! RuntimeException │                       │
  │◀──────────────────── catch ─────┤                          │                       │
  │                                 │                          │                       │
  │   sleep(2000ms)  ← 백오프 2초   │                          │                       │
  │                                 │                          │                       │
  │   attempt=3                     │                          │                       │
  ├─────────────── processWithRetry ▶│                          │                       │
  │                                 ├── 실패! RuntimeException │                       │
  │◀──────────────────── catch ─────┤                          │                       │
  │                                 │                          │                       │
  │   MAX_RETRY 도달!               │                          │                       │
  │                                 │                          │                       │
  ├── sendToDlq(record, exception) ────────────────────────────▶│                       │
  │                                                            ├── send().get(10s) ────▶│
  │                                                            │◀──── ACK ─────────────│
  │                                                            │                       │
  │◀────────────────────────── 격리 완료 ──────────────────────│                       │
  │                                                                                    │
  ├── ack.acknowledge() ── offset 33 커밋                                              │
  │                                                                                    │
  ├── 다음 메시지로 진행                                                               │
```

---

## DLQ Consumer 모니터링 흐름

```
Kafka (DLQ)                 DLQ Consumer                    메모리 (List)
────────────               ──────────────                  ──────────────
  │                             │                               │
  ├── record(dlqOffset=0) ─────▶│                               │
  │                             ├── 헤더에서 원본 정보 추출      │
  │                             │   X-Original-Topic            │
  │                             │   X-Original-Partition        │
  │                             │   X-Error-Message             │
  │                             │                               │
  │                             ├── 로그 출력 (ERROR 레벨)       │
  │                             │   "[DLQ Monitor] 실패 메시지   │
  │                             │    수신..."                   │
  │                             │                               │
  │                             ├── 메모리에 저장 ──────────────▶│ recentDlqMessages
  │                             │   (최근 50건 보관)             │ .add(entry)
  │                             │                               │
  │                             ├── ack.acknowledge()           │
  │                             │                               │
  │                                                             │
  │                     API 요청: GET /dlq/messages              │
  │                             │◀─────────────────────────────│
  │                             ├── return recentDlqMessages    │
```

---

## 관찰 포인트 정리

```
1. 지수 백오프 실측
   attempt=1 후 1000ms 대기 → 실제 1003ms (오차 3ms)
   attempt=2 후 2000ms 대기 → 실제 2000ms (정확)
   → Thread.sleep()의 정밀도는 ms 단위에서 충분

2. 병렬 처리
   2건이 다른 파티션(0, 2)에 배치됨
   → 각각 다른 Consumer 스레드가 동시 처리
   → 전체 소요 3초 (직렬이었으면 6초)

3. DLQ 전송 시간
   send().get() 동기 전송: 114ms (메시지 1), 130ms (메시지 2)
   → DLQ 토픽도 replicas=3, acks=all이므로 복제 대기 포함

4. 원본 토픽 ACK 타이밍
   DLQ 전송 성공 후에 원본 토픽 ACK
   → DLQ 전송이 실패하면? 로그로만 남김 (최후의 수단)
   → 원본 토픽에서는 ACK → 다음 메시지로 진행
   → DLQ 전송 실패는 극히 드문 상황 (Kafka 클러스터 자체 장애)
```
