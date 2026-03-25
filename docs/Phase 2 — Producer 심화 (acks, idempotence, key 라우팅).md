# Phase 2 — Producer 심화 (acks, idempotence, key 라우팅)

---

## 실험 1: acks 설정별 비교

### acks란?

Producer가 메시지를 보낸 후, **"누구한테 확인을 받아야 성공으로 칠 것인가"**를 결정하는 설정.

```
┌──────────┬────────────────────────────┬──────────┬──────────────────────────┐
│ acks     │ 동작                        │ 안전성   │ 적합한 용도               │
├──────────┼────────────────────────────┼──────────┼──────────────────────────┤
│ 0        │ 브로커 응답 안 기다림        │ ❌ 유실  │ 로그, 클릭 이벤트         │
│ 1        │ Leader만 확인              │ △ 위험   │ 중요도 낮은 이벤트         │
│ all(-1)  │ Leader + 모든 ISR 확인     │ ✅ 안전  │ 주문, 결제, 쿠폰 발급     │
└──────────┴────────────────────────────┴──────────┴──────────────────────────┘
```

### 가시화: 각 acks 설정의 동작 흐름

```
acks=0 (Fire and Forget)
──────────────────────────────────────────
Producer ──→ [메시지] ──→ Broker (Leader)
         ↑                    │
         └── 즉시 리턴         │
             (응답 안 기다림)   ├── Follower 1에 복제
                               └── Follower 2에 복제
                                    (복제 여부 모름)

→ 가장 빠름. 브로커가 죽어있어도 에러를 모름.


acks=1 (Leader Only)
──────────────────────────────────────────
Producer ──→ [메시지] ──→ Broker (Leader)
                              │
                              ├── 로컬 로그에 기록 ✅
                              │
                         ◀── ACK 반환
                              │
                              ├── Follower 1에 복제 (비동기)
                              └── Follower 2에 복제 (비동기)

→ Leader가 ACK 보낸 직후 죽으면? Follower에 아직 복제 안 됨 → 유실!


acks=all (All ISR)
──────────────────────────────────────────
Producer ──→ [메시지] ──→ Broker (Leader)
                              │
                              ├── 로컬 로그에 기록 ✅
                              │
                              ├── Follower 1에 복제 ✅
                              ├── Follower 2에 복제 ✅
                              │
                         ◀── ACK 반환 (모든 ISR 확인 후)

→ Leader가 죽어도 Follower에 이미 복제됨 → 유실 없음!
→ min.insync.replicas=2와 조합 → 1대 죽어도 쓰기 성공
```

### 실측 결과

**단건 (최초 발행 — Cold Start 포함)**
```
acks=0  : 271ms   ← Producer 최초 초기화 비용 포함
acks=1  : 102ms
acks=all: 59ms
```

**벌크 100건**
```
acks=0  : 103ms
acks=1  : 217ms
acks=all: 160ms
```

**벌크 1000건**
```
acks=0  : 969ms
acks=1  : 347ms
acks=all: 117ms   ← 가장 빠름?!
```

### 왜 acks=all이 acks=0보다 빠른가?

직관적으로 acks=all이 가장 느려야 하는데, 실측에서 가장 빠르다. 이유:

```
┌─────────────────────────────────────────────────────────────────────┐
│  핵심: KafkaTemplate.send()는 비동기(non-blocking)이다.             │
│                                                                     │
│  우리 코드에서 측정하는 시간:                                        │
│  long start = System.currentTimeMillis();                           │
│  kafkaTemplate.send(TOPIC, key, event);  ← 큐에 넣고 즉시 반환      │
│  long elapsed = System.currentTimeMillis() - start;                 │
│                                                                     │
│  실제 브로커 왕복 시간은 whenComplete() 콜백에서만 알 수 있다.       │
│                                                                     │
│  send()가 빠른 이유:                                                 │
│  1. 메시지를 내부 버퍼(RecordAccumulator)에 넣음                     │
│  2. 별도 Sender 스레드가 배치로 묶어서 브로커에 전송                  │
│  3. send() 자체는 버퍼에 넣는 시간만 소요                            │
│                                                                     │
│  acks=all + idempotence=true 조합에서 배치 최적화가 더 적극적이라    │
│  벌크 전송 시 오히려 빠르게 측정될 수 있음.                          │
└─────────────────────────────────────────────────────────────────────┘

결론: "acks=all이 느리다"는 동기 전송 기준.
      비동기에서는 배치 크기, 버퍼 상태, 네트워크에 따라 달라진다.
      성능 차이보다 안전성 차이가 acks 선택의 핵심 기준이다.
```

---

## 실험 2: Idempotence (멱등성 Producer)

### Idempotence란?

Producer가 같은 메시지를 여러 번 보내도 **브로커에 한 번만 기록**되게 하는 기능.

```
┌─────────────────────────────────────────────────────────────────────┐
│  문제 상황: 네트워크 타임아웃                                        │
│                                                                     │
│  Producer ──→ [메시지] ──→ Broker (기록 완료 ✅)                     │
│          ↑                    │                                      │
│          │              ACK 전송 중... 네트워크 장애!                 │
│          │                    ✗                                      │
│          └── "실패인 줄 알고 재전송"                                 │
│                                                                     │
│  Producer ──→ [같은 메시지] ──→ Broker (또 기록? → 중복!)            │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  해결: enable.idempotence=true                                      │
│                                                                     │
│  Producer가 각 메시지에 (PID, Sequence Number)를 붙인다.            │
│                                                                     │
│  Producer ──→ [PID=1, Seq=5, 메시지] ──→ Broker (기록 ✅)           │
│          ↑                                    │                      │
│          │              ACK 전송 중... 실패!                         │
│          └── 재전송                                                  │
│  Producer ──→ [PID=1, Seq=5, 메시지] ──→ Broker                     │
│                                              │                       │
│                                     "PID=1, Seq=5 이미 있네?"       │
│                                     → 무시 (중복 감지)               │
│                                     → ACK 반환 (성공 처리)           │
└─────────────────────────────────────────────────────────────────────┘
```

### 설정

```yaml
# application.yml
spring.kafka.producer:
  acks: all                                        # 필수 — idempotence는 acks=all에서만 동작
  properties:
    enable.idempotence: true                       # 멱등성 ON
    max.in.flight.requests.per.connection: 5       # 최대 5 (idempotence 호환 상한)
```

### 제약사항

```
┌─────────────────────────────────────────────────────────────────────┐
│  idempotence = true 사용 조건:                                      │
│                                                                     │
│  1. acks=all 이어야 함 (0이나 1이면 불가)                           │
│  2. max.in.flight.requests.per.connection ≤ 5                       │
│  3. retries > 0 (재전송이 가능해야 중복 감지도 의미 있음)            │
│                                                                     │
│  주의: idempotence는 단일 Producer 세션 내에서만 보장.               │
│  Producer가 재시작되면 새 PID를 받으므로, 이전 세션의 중복은 못 감지.│
│  → 이 한계를 극복하려면 Consumer 쪽 멱등성 처리가 추가로 필요.      │
│  → Phase 5에서 event_handled 테이블로 해결 예정.                    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 실험 3: Partition Key 라우팅 검증

### 실측 결과

```
orderId=1 → attempt 1: partition=0, offset=144
orderId=1 → attempt 2: partition=0, offset=145
orderId=1 → attempt 3: partition=0, offset=146
                                                    ← 같은 key = 같은 파티션!

orderId=2 → attempt 1: partition=2, offset=71
orderId=2 → attempt 2: partition=2, offset=72
orderId=2 → attempt 3: partition=2, offset=73
                                                    ← 같은 key = 같은 파티션!

orderId=3 → attempt 1: partition=2, offset=74
orderId=3 → attempt 2: partition=2, offset=75
orderId=3 → attempt 3: partition=2, offset=76

orderId=4 → attempt 1: partition=1, offset=102
orderId=4 → attempt 2: partition=1, offset=103
orderId=4 → attempt 3: partition=1, offset=104

orderId=5 → attempt 1: partition=0, offset=147
orderId=5 → attempt 2: partition=0, offset=148
orderId=5 → attempt 3: partition=0, offset=149

orderId=6 → attempt 1: partition=1, offset=105
orderId=6 → attempt 2: partition=1, offset=106
orderId=6 → attempt 3: partition=1, offset=107
```

### 분배 요약

```
Partition 0: orderId=1, orderId=5     (6건)
Partition 1: orderId=4, orderId=6     (6건)
Partition 2: orderId=2, orderId=3     (6건)

→ 균등 분배 ✅
→ 같은 key는 100% 같은 파티션 ✅
→ offset이 순차 증가 ✅ (같은 파티션 내 순서 보장)
```

### 라우팅 알고리즘

```
DefaultPartitioner:

  1. key가 있으면:
     partition = murmur2(key.getBytes()) % numPartitions

  2. key가 없으면:
     Round-Robin 또는 Sticky Partitioner (Kafka 2.4+)

  → key가 null이면 파티션이 메시지마다 바뀔 수 있다!
  → 순서 보장이 필요하면 반드시 key를 지정해야 한다.
```

---

## 프로젝트 구조 (Phase 2 추가분)

```
src/main/java/com/pipeline/
├── config/
│   ├── KafkaTopicConfig.java          # Phase 1
│   └── KafkaProducerConfig.java       # Phase 2 — acks별 3개 KafkaTemplate Bean
├── producer/
│   ├── OrderEventProducer.java        # Phase 1 (acks=all로 변경)
│   ├── AcksComparisonProducer.java    # Phase 2 — acks 비교 실험
│   └── KeyRoutingProducer.java        # Phase 2 — key 라우팅 검증
└── api/
    ├── KafkaTestController.java       # Phase 1
    └── ProducerExperimentController.java  # Phase 2 — 실험 API
```

---

## 실험 API 정리

```bash
# acks 비교 — 단건
curl -X POST http://localhost:8085/api/experiment/producer/acks-compare/54333

# acks 비교 — 벌크 1000건
curl -X POST http://localhost:8085/api/experiment/producer/acks-compare/bulk/1000

# key 라우팅 검증 — orderId 1~6, 각 3번
curl -X POST "http://localhost:8085/api/experiment/producer/key-routing?repeatPerKey=3"
```

---

## 다음 단계: Phase 3 — Consumer 심화

- Consumer Group 리밸런싱 시나리오 재현
- Manual ACK vs Auto Commit 차이 실험
- Batch Listener 구현
- Consumer가 죽었을 때 다른 Consumer가 파티션을 이어받는지 확인
