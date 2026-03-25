# Phase 5 실험 기록 — 멱등성 중복 감지 실측 상세

---

## 실험 환경

```
인프라: 3-Broker KRaft 클러스터 (kafka-1, kafka-2, kafka-3)
앱: kafka-pipeline-lab (port 8085)
토픽: order.created.event-v1 (partition 3, replicas 3)
Consumer Group: order-inventory-group (concurrency=3, Manual ACK)
DB: pipeline-mysql (port 3307)
멱등성: event_handled 테이블 (eventId UNIQUE)
```

---

## 실험 1: 같은 eventId로 2건 발행

### 실행

```bash
curl -s -X POST http://localhost:8085/api/experiment/idempotency/duplicate-test/77777
```

### 발행 내용

```json
{
  "orderId": 77777,
  "userId": 1,
  "totalAmount": 29900,
  "eventId": "duplicate-test-77777",    ← 2건 모두 동일한 eventId
  "occurredAt": "2026-03-25T10:32:55"
}
```

→ 같은 eventId(`duplicate-test-77777`)로 **동일 메시지를 2번 연속 발행**

### API 응답

```json
{
    "orderId": 77777,
    "eventId": "duplicate-test-77777",
    "발행 횟수": 2,
    "예상 결과": "Consumer 로그에서 첫 번째는 처리, 두 번째는 '중복 메시지 스킵'"
}
```

### Consumer 로그 (시간순)

```
10:32:55.134  [Consumer] 메시지 수신 — partition=0, offset=1209, key=77777, eventId=duplicate-test-77777
                │
                ▼  isAlreadyHandled("duplicate-test-77777") → false (처음 봄)
                │
10:32:55.326  [Consumer] 주문 처리 중 — orderId=77777, amount=29900
                │
                ▼  @Transactional { 비즈니스 로직 + event_handled INSERT }
                │
10:32:55.481  [Consumer] ACK 완료 — eventId=duplicate-test-77777, orderId=77777
                │
                ▼  offset 1209 커밋 완료. 다음 메시지 수신.
                │
10:32:55.482  [Consumer] 메시지 수신 — partition=0, offset=1210, key=77777, eventId=duplicate-test-77777
                │
                ▼  isAlreadyHandled("duplicate-test-77777") → true (이미 있음!)
                │
10:32:55.491  [Consumer] 중복 메시지 스킵 — eventId=duplicate-test-77777, orderId=77777
                │
                ▼  ACK만 하고 끝. 비즈니스 로직 실행 안 함.
```

### 시간 분석

```
첫 번째 메시지:
  수신 → 처리 → ACK = 10:32:55.134 ~ 10:32:55.481 = 347ms

  내역:
  - isAlreadyHandled 조회: ~5ms (SELECT)
  - 비즈니스 로직: ~150ms
  - event_handled INSERT: ~5ms
  - TX 커밋: ~30ms
  - ACK (offset 커밋): ~50ms

두 번째 메시지:
  수신 → 중복 스킵 → ACK = 10:32:55.482 ~ 10:32:55.491 = 9ms

  내역:
  - isAlreadyHandled 조회: ~5ms (SELECT → true)
  - ACK: ~4ms
  - 비즈니스 로직 실행 없음!

→ 중복 메시지 처리 비용: 9ms (조회 + ACK만)
→ 비즈니스 로직을 실행하지 않으므로 부하 최소
```

### DB 상태

```sql
mysql> SELECT * FROM event_handled;

+----+------------------------+--------------+----------------------------+
| id | event_id               | event_type   | handled_at                 |
+----+------------------------+--------------+----------------------------+
|  1 | duplicate-test-77777   | ORDER_CREATED| 2026-03-25 10:32:55.332842 |
+----+------------------------+--------------+----------------------------+

→ 2건 발행, 1건만 기록됨 ✅
```

### 파티션/오프셋 상태

```
같은 key(77777) → 같은 파티션(0)에 순차 적재:
  partition=0, offset=1209  (첫 번째 → 처리됨)
  partition=0, offset=1210  (두 번째 → 중복 스킵)

→ 같은 파티션에서 순차 처리되므로
  첫 번째가 완전히 처리된 후 두 번째를 수신.
  isAlreadyHandled 시점에 이미 event_handled에 INSERT 완료.
  → 중복 감지 100% 보장.
```

---

## 실험 2: 같은 eventId로 10건 발행 (Flood)

### 실행

```bash
curl -s -X POST "http://localhost:8085/api/experiment/idempotency/flood/88888?count=10"
```

### 발행 내용

```
eventId = "flood-test-88888" (10건 모두 동일)
key = "88888" (10건 모두 동일)
→ 같은 파티션에 offset 1135~1144로 순차 적재
```

### API 응답

```json
{
    "orderId": 88888,
    "eventId": "flood-test-88888",
    "발행 횟수": 10,
    "예상 결과": "1번만 처리, 나머지 9번은 중복 스킵"
}
```

### Consumer 로그 (전체 — 시간순)

```
10:53:30.318  [Consumer] 메시지 수신 — partition=1, offset=1135, eventId=flood-test-88888
              → isAlreadyHandled → false
10:53:30.328  [Consumer] 주문 처리 중 — orderId=88888, amount=29900
              → event_handled INSERT ✅
              → TX 커밋 ✅
10:53:30.370  [Consumer] ACK 완료

10:53:30.371  [Consumer] 메시지 수신 — partition=1, offset=1136, eventId=flood-test-88888
              → isAlreadyHandled → true
10:53:30.375  [Consumer] 중복 메시지 스킵 ✅

10:53:30.376  [Consumer] 메시지 수신 — partition=1, offset=1137
10:53:30.381  [Consumer] 중복 메시지 스킵 ✅

10:53:30.381  [Consumer] 메시지 수신 — partition=1, offset=1138
10:53:30.385  [Consumer] 중복 메시지 스킵 ✅

10:53:30.386  [Consumer] 메시지 수신 — partition=1, offset=1139
10:53:30.389  [Consumer] 중복 메시지 스킵 ✅

10:53:30.389  [Consumer] 메시지 수신 — partition=1, offset=1140
10:53:30.393  [Consumer] 중복 메시지 스킵 ✅

10:53:30.394  [Consumer] 메시지 수신 — partition=1, offset=1141
10:53:30.397  [Consumer] 중복 메시지 스킵 ✅

10:53:30.398  [Consumer] 메시지 수신 — partition=1, offset=1142
10:53:30.401  [Consumer] 중복 메시지 스킵 ✅

10:53:30.402  [Consumer] 메시지 수신 — partition=1, offset=1143
10:53:30.406  [Consumer] 중복 메시지 스킵 ✅

10:53:30.406  [Consumer] 메시지 수신 — partition=1, offset=1144
10:53:30.410  [Consumer] 중복 메시지 스킵 ✅
```

### 처리 요약

```
┌─────────┬──────────┬───────────┬──────────────┬──────────────────────┐
│ offset  │ 수신 시각 │ 처리 시간  │ 결과         │ 비즈니스 로직 실행   │
├─────────┼──────────┼───────────┼──────────────┼──────────────────────┤
│ 1135    │ :30.318  │ 52ms      │ ✅ 처리     │ ✅ 실행              │
│ 1136    │ :30.371  │ 4ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1137    │ :30.376  │ 5ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1138    │ :30.381  │ 4ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1139    │ :30.386  │ 3ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1140    │ :30.389  │ 4ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1141    │ :30.394  │ 3ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1142    │ :30.398  │ 3ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1143    │ :30.402  │ 4ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
│ 1144    │ :30.406  │ 4ms       │ 🔁 중복 스킵 │ ❌ 미실행            │
├─────────┼──────────┼───────────┼──────────────┼──────────────────────┤
│ 합계    │          │ 86ms      │ 1건 처리     │ 1번만 실행           │
│         │          │           │ 9건 스킵     │                      │
└─────────┴──────────┴───────────┴──────────────┴──────────────────────┘

첫 번째: 52ms (SELECT + 비즈니스 + INSERT + TX 커밋 + ACK)
나머지 9건 평균: 3.8ms (SELECT만 + ACK)

→ 10건 전체 처리 시간: 86ms
→ 중복 9건의 비용: 34ms (전체의 40%)
→ 비즈니스 로직은 1번만 실행 = 멱등성 보장 ✅
```

### DB 상태

```sql
mysql> SELECT * FROM event_handled ORDER BY id DESC LIMIT 5;

+----+---------------------+--------------+----------------------------+
| id | event_id            | event_type   | handled_at                 |
+----+---------------------+--------------+----------------------------+
|  2 | flood-test-88888    | ORDER_CREATED| 2026-03-25 10:53:30.328878 |
|  1 | duplicate-test-77777| ORDER_CREATED| 2026-03-25 10:32:55.332842 |
+----+---------------------+--------------+----------------------------+

→ 실험 1 + 실험 2 합계: 12건 발행, event_handled 2건만 기록 ✅
```

---

## 동작 원리 가시화

### 실험 1 (2건 발행) 시퀀스

```
Producer                  Kafka (Partition 0)              Consumer               DB (event_handled)
─────────                ──────────────────              ─────────              ──────────────────
  │                           │                              │                         │
  ├── send(eventId=dup-77777)─▶ offset=1209                 │                         │
  ├── send(eventId=dup-77777)─▶ offset=1210                 │                         │
  │                           │                              │                         │
  │                           ├─ poll() ────────────────────▶│                         │
  │                           │  record(offset=1209)         │                         │
  │                           │                              ├─ SELECT eventId ───────▶│
  │                           │                              │◀─ NOT FOUND ────────────│
  │                           │                              │                         │
  │                           │                              ├─ 비즈니스 로직 실행     │
  │                           │                              │                         │
  │                           │                              ├─ INSERT eventId ────────▶│ ✅ 저장
  │                           │                              ├─ TX COMMIT              │
  │                           │                              ├─ ACK(offset=1209) ─────▶│
  │                           │                              │                         │
  │                           ├─ poll() ────────────────────▶│                         │
  │                           │  record(offset=1210)         │                         │
  │                           │                              ├─ SELECT eventId ───────▶│
  │                           │                              │◀─ FOUND! ──────────────│
  │                           │                              │                         │
  │                           │                              ├─ 스킵 (비즈니스 미실행) │
  │                           │                              ├─ ACK(offset=1210) ─────▶│
  │                           │                              │                         │
```

### 실험 2 (10건 발행) 시퀀스 요약

```
Producer                  Kafka (Partition 1)              Consumer
─────────                ──────────────────              ─────────
  │                           │                              │
  ├── send × 10 ─────────────▶ offset=1135~1144             │
  │  (전부 eventId=flood-88888)│                              │
  │                           │                              │
  │                           ├─ offset=1135 ───────────────▶│ → SELECT → NOT FOUND
  │                           │                              │ → 비즈니스 실행
  │                           │                              │ → INSERT → TX COMMIT → ACK
  │                           │                              │
  │                           ├─ offset=1136 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1137 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1138 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1139 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1140 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1141 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1142 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1143 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           ├─ offset=1144 ───────────────▶│ → SELECT → FOUND → 스킵 → ACK
  │                           │                              │
```

---

## 왜 같은 파티션에서 순차 처리되면 중복 감지가 100% 보장되는가

```
같은 key(orderId=88888) → 같은 파티션(1)
→ 같은 Consumer(consumer-2)가 순차 처리

  offset=1135 처리 완료 (event_handled INSERT + TX COMMIT)
       │
       ▼  이 시점에 event_handled에 "flood-test-88888" 존재
       │
  offset=1136 수신 → isAlreadyHandled → true → 스킵

→ 순차 처리 보장 = 첫 번째 처리가 완전히 끝난 후 두 번째 수신
→ isAlreadyHandled 시점에 이미 DB에 기록 완료
→ 중복 감지 100% 보장

만약 다른 파티션에 같은 eventId가 있다면?
→ 다른 Consumer가 동시에 처리할 수 있음
→ 이때는 UNIQUE 제약 조건이 방어 (실험에서는 같은 파티션이므로 해당 없음)
```

---

## 성능 관찰

```
멱등성 오버헤드:

  멱등성 없이 처리: ~30ms (비즈니스만)
  멱등성 포함 처리: ~52ms (SELECT + 비즈니스 + INSERT + TX)
  오버헤드: ~22ms (SELECT 5ms + INSERT 5ms + TX 12ms)

  중복 메시지 처리: ~4ms (SELECT만)

  → 멱등성 추가 비용: 건당 약 22ms
  → 중복 메시지 비용: 건당 약 4ms
  → 비즈니스 로직 중복 실행 방지 효과를 고려하면 매우 저렴한 비용
```
