# Phase 3 — Consumer 심화 (리밸런싱, Manual ACK, Batch, LAG)

---

## Manual ACK vs Auto Commit

### 동작 흐름 비교

```
Manual ACK (enable.auto.commit=false, ack-mode=MANUAL)
──────────────────────────────────────────────────────
  poll() → 메시지 수신
       │
       ▼
  비즈니스 로직 처리
       │
       ├── 성공 → ack.acknowledge() → offset 커밋 ✅
       │
       └── 실패 → acknowledge() 안 함 → offset 커밋 안 됨
                                       → 다음 poll()에서 같은 메시지 다시 수신
                                       → 재처리 가능!


Auto Commit (enable.auto.commit=true, auto.commit.interval.ms=5000)
──────────────────────────────────────────────────────
  poll() → 메시지 수신
       │                    ┌── 5초 경과 → 자동 offset 커밋 ⚠️
       ▼                    │
  비즈니스 로직 처리 중...    │
       │                    │
       └── 처리 실패! ──────┘
                            │
                   이미 offset이 커밋됨 → 메시지 유실!
                   다음 poll()에서 이 메시지를 다시 받을 수 없음
```

### 왜 Manual ACK가 기본인가?

```
┌─────────────────────────────────────────────────────────────────────┐
│  Auto Commit의 위험:                                                │
│                                                                     │
│  1. 처리 중 앱이 죽으면?                                            │
│     → 이미 커밋된 offset 이후의 메시지만 받음                       │
│     → 처리 중이던 메시지는 유실                                     │
│                                                                     │
│  2. 처리 실패했는데 커밋 타이밍이 겹치면?                            │
│     → 실패한 메시지의 offset이 커밋됨                               │
│     → 재처리 불가                                                   │
│                                                                     │
│  Manual ACK의 안전:                                                 │
│                                                                     │
│  1. 처리 완료 후에만 offset 커밋                                    │
│  2. 실패 시 offset 커밋 안 됨 → 재처리 보장                        │
│  3. at-least-once 보장 (최소 1번은 처리)                            │
│     → 대신 중복 처리 가능성 → Phase 5 멱등성으로 해결               │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Batch Listener

### 단건 vs 배치

```
단건 처리 (OrderEventConsumer):
──────────────────────────────
  poll() → [msg1]     → 처리 → ACK
  poll() → [msg2]     → 처리 → ACK
  poll() → [msg3]     → 처리 → ACK
  ...
  총 3번의 처리 + 3번의 ACK

배치 처리 (BatchOrderEventConsumer):
──────────────────────────────
  poll() → [msg1, msg2, msg3]  → 한번에 처리 → ACK 1번
  ...
  총 1번의 처리 + 1번의 ACK

  배치가 유리한 경우:
  - DB bulk insert (INSERT INTO ... VALUES (...), (...), (...))
  - 외부 API batch 호출
  - 처리량(throughput)이 중요할 때

  배치의 위험:
  - 배치 중 1건 실패 → 전체 재처리? 실패 건만 DLQ?
  - 배치 크기가 너무 크면 메모리 이슈
```

### 설정

```java
// batchManualAckFactory
container.setBatchListener(true);                           // 배치 모드 ON
container.getContainerProperties().setAckMode(MANUAL_IMMEDIATE);  // 즉시 ACK
configs.put(MAX_POLL_RECORDS_CONFIG, 50);                   // 한 번에 최대 50건
```

---

## Consumer Group — 독립 소비 실측 확인

### 같은 토픽, 다른 Consumer Group

```
order.created.event-v1 (offset 1203/1095/1037)
    │
    ├── Consumer Group: order-inventory-group (단건 처리)
    │   ├── consumer-1 → Partition 0  offset: 1203  LAG: 0
    │   ├── consumer-2 → Partition 1  offset: 1095  LAG: 0
    │   └── consumer-3 → Partition 2  offset: 1037  LAG: 0
    │
    └── Consumer Group: order-batch-group (배치 처리)
        ├── consumer-7 → Partition 0  offset: 1203  LAG: 0
        ├── consumer-8 → Partition 1  offset: 1095  LAG: 0
        └── consumer-9 → Partition 2  offset: 1037  LAG: 0

→ 같은 메시지를 두 Group이 독립적으로 소비!
→ Group A가 다 읽어도 Group B와 무관
→ 각 Group은 자체 offset을 관리
→ 이것이 Kafka의 1:N 분배 구조
```

---

## LAG (적체) — 실측 결과

### LAG란?

```
LAG = LOG-END-OFFSET - CURRENT-OFFSET
    = 아직 처리 안 된 메시지 수

LOG-END-OFFSET: 토픽에 마지막으로 기록된 offset
CURRENT-OFFSET: Consumer가 마지막으로 커밋한 offset
```

### 실험: 느린 Consumer의 LAG 변화

```
1) 정상 속도 (delay=0ms) + 50건 발행
   ┌─────────┬────────────────┬────────────────┬─────┐
   │Partition │ CURRENT-OFFSET │ LOG-END-OFFSET │ LAG │
   ├─────────┼────────────────┼────────────────┼─────┤
   │    0     │      20        │      20        │  0  │
   │    1     │      10        │      10        │  0  │
   │    2     │      20        │      20        │  0  │
   └─────────┴────────────────┴────────────────┴─────┘
   → 즉시 소비 완료


2) 느린 속도 (delay=3000ms) + 30건 발행 → 5초 후
   ┌─────────┬────────────────┬────────────────┬─────┐
   │Partition │ CURRENT-OFFSET │ LOG-END-OFFSET │ LAG │
   ├─────────┼────────────────┼────────────────┼─────┤
   │    0     │      22        │      32        │ 10  │  ← LAG 발생!
   │    1     │      12        │      16        │  4  │
   │    2     │      22        │      32        │ 10  │
   └─────────┴────────────────┴────────────────┴─────┘
   → 3초/건 처리인데 메시지가 계속 들어오니까 적체


3) 지연 해제 (delay=0ms) → 15초 후
   ┌─────────┬────────────────┬────────────────┬─────┐
   │Partition │ CURRENT-OFFSET │ LOG-END-OFFSET │ LAG │
   ├─────────┼────────────────┼────────────────┼─────┤
   │    0     │      32        │      32        │  0  │  ← 복구!
   │    1     │      16        │      16        │  0  │
   │    2     │      32        │      32        │  0  │
   └─────────┴────────────────┴────────────────┴─────┘
   → 적체 해소
```

### LAG 모니터링이 중요한 이유

```
LAG이 계속 쌓이면?
  → Consumer가 처리량을 따라잡지 못하는 것
  → 실시간 처리가 지연됨

대응 방법:
  1. Consumer 인스턴스 수 증가 (파티션 수 이하로)
  2. 배치 처리로 throughput 향상
  3. Consumer 로직 최적화 (불필요한 I/O 제거)
  4. 파티션 수 자체를 늘려서 병렬도 확장

모니터링:
  - Kafka UI에서 Consumer Group 탭 → LAG 실시간 확인
  - Prometheus + Grafana에서 kafka_consumer_lag 메트릭 대시보드
  - LAG 임계값 알림 설정 (예: LAG > 1000이면 Slack 알림)
```

---

## 리밸런싱 (Rebalancing)

### 리밸런싱이 발생하는 경우

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. Consumer가 Consumer Group에 합류할 때                           │
│     → 새 인스턴스 배포, 스케일 아웃                                 │
│                                                                     │
│  2. Consumer가 Consumer Group에서 이탈할 때                         │
│     → 앱 죽음, 네트워크 장애, session.timeout 초과                  │
│                                                                     │
│  3. max.poll.interval.ms 초과                                       │
│     → 처리가 너무 느려서 다음 poll()을 제때 못 함                   │
│     → "이 Consumer는 죽은 거 아닌가?" → 리밸런싱                   │
│                                                                     │
│  4. 토픽의 파티션 수가 변경될 때                                    │
│     → 파티션 추가 시 재분배 필요                                    │
└─────────────────────────────────────────────────────────────────────┘
```

### 리밸런싱 중 발생하는 일

```
리밸런싱 전:
  Consumer-1 → Partition 0
  Consumer-2 → Partition 1
  Consumer-3 → Partition 2

Consumer-3 죽음 → 리밸런싱 시작:
  1. 모든 Consumer가 파티션 소유권을 반납
  2. Group Coordinator가 파티션을 재분배
  3. 리밸런싱 중에는 메시지 소비 중단! (Stop-the-World)

리밸런싱 후:
  Consumer-1 → Partition 0, Partition 2  ← Partition 2를 이어받음
  Consumer-2 → Partition 1

주의:
  리밸런싱은 비용이 크다.
  모든 Consumer가 잠시 멈추고, 파티션 재할당 후 다시 시작.
  자주 발생하면 처리량 저하.
```

### 관련 설정

```yaml
session.timeout.ms: 30000     # Consumer가 heartbeat를 이 시간 안에 안 보내면 죽은 걸로 간주
heartbeat.interval.ms: 10000  # heartbeat 전송 간격 (session.timeout의 1/3 권장)
max.poll.interval.ms: 120000  # poll() 호출 간격 최대치. 초과하면 리밸런싱
```

---

## 실험 API 정리

```bash
# SlowConsumer 지연 설정 (ms)
curl -X POST http://localhost:8085/api/experiment/consumer/slow/delay/3000

# inventory 토픽에 메시지 발행
curl -X POST http://localhost:8085/api/experiment/consumer/inventory/publish/50

# SlowConsumer 상태 확인
curl http://localhost:8085/api/experiment/consumer/slow/status

# Consumer Group LAG 확인
docker exec kafka-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group inventory-slow-group

# 전체 Consumer Group 목록
docker exec kafka-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list

# 실험 가이드
curl http://localhost:8085/api/experiment/consumer/guide
```

---

## 프로젝트 구조 (Phase 3 추가분)

```
src/main/java/com/pipeline/
├── config/
│   ├── KafkaTopicConfig.java          # Phase 1
│   ├── KafkaProducerConfig.java       # Phase 2
│   └── KafkaConsumerConfig.java       # Phase 3 — 3가지 Factory (manual/batch/auto)
├── consumer/
│   ├── OrderEventConsumer.java        # Phase 1 → manualAckFactory로 변경
│   ├── BatchOrderEventConsumer.java   # Phase 3 — 배치 Consumer
│   └── SlowConsumer.java             # Phase 3 — LAG/리밸런싱 실험용
└── api/
    ├── KafkaTestController.java
    ├── ProducerExperimentController.java
    └── ConsumerExperimentController.java  # Phase 3 — 실험 API
```

---

## 다음 단계: Phase 4 — Transactional Outbox

- DB 트랜잭션과 Kafka 발행의 원자성 문제 해결
- Outbox 테이블 설계 + Polling 방식 구현
- Spring @Transactional 안에서 Outbox 저장 → 릴레이가 Kafka로 전송
