# Phase 4 — Transactional Outbox 패턴 구현

---

## 해결하는 문제

```
문제: DB 트랜잭션과 Kafka 발행의 원자성

  @Transactional
  public void createOrder(Order order) {
      orderRepository.save(order);           // ① DB 저장
      kafkaTemplate.send("order.created", order);  // ② Kafka 발행
  }

  시나리오 A: ① 성공 → ② 실패 (Kafka 장애)
  → 주문은 DB에 있는데, 이벤트는 발행 안 됨 → 후속 서비스(재고, 포인트)가 모름

  시나리오 B: ② 성공 → ① 롤백 (DB 에러)
  → 주문은 DB에 없는데, 이벤트는 발행됨 → 존재하지 않는 주문에 대해 처리 시작

  → 둘 다 정합성이 깨진다.
```

---

## Outbox 패턴 흐름

```
┌──────────────────────────────────────────────────────────────────┐
│                         TX 1 (비즈니스)                           │
│                                                                  │
│  ┌──────────────────┐   ┌──────────────────┐                    │
│  │   order 테이블    │   │  outbox_event    │                    │
│  │                  │   │  테이블           │                    │
│  │  INSERT 주문     │   │  INSERT 이벤트    │   ← 같은 TX!      │
│  │  (orderId=25102) │   │  (status=PENDING) │                    │
│  └──────────────────┘   └──────────────────┘                    │
│                                                                  │
│  커밋 → 둘 다 확정  /  롤백 → 둘 다 취소                        │
└──────────────────────────────────────────────────────────────────┘
                                │
                                │  Polling (5초 간격)
                                │  SELECT WHERE status='PENDING'
                                ▼
                    ┌──────────────────────┐
                    │  OutboxRelayService   │
                    │  (스케줄러)            │
                    └──────────┬───────────┘
                               │
                          send().get()  ← 동기 대기
                               │
                               ▼
                    ┌──────────────────────┐
                    │       Kafka          │
                    └──────────────────────┘
                               │
                          성공 → markPublished()
                          실패 → markFailed()
                               │
                               ▼
                    ┌──────────────────────┐
                    │  outbox_event 상태    │
                    │  PENDING → PUBLISHED │
                    └──────────────────────┘
```

---

## Outbox 테이블 설계

```sql
CREATE TABLE outbox_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic          VARCHAR(200) NOT NULL,           -- Kafka 토픽명
    partition_key  VARCHAR(100),                    -- Partition Key (orderId 등)
    payload        TEXT NOT NULL,                   -- 이벤트 JSON
    event_type     VARCHAR(100) NOT NULL,           -- ORDER_CREATED, COUPON_ISSUED 등
    status         VARCHAR(20) NOT NULL,            -- PENDING / PUBLISHED / FAILED
    retry_count    INT NOT NULL DEFAULT 0,          -- 재시도 횟수
    created_at     DATETIME(6) NOT NULL,            -- 생성 시각
    published_at   DATETIME(6),                     -- 발행 완료 시각
    error_message  VARCHAR(500),                    -- 실패 사유

    INDEX idx_outbox_status_created (status, created_at)
);
```

### 인덱스 설계

```
idx_outbox_status_created (status, created_at)

→ Relay가 매 5초마다 실행하는 쿼리:
  SELECT * FROM outbox_event WHERE status='PENDING' ORDER BY created_at LIMIT 50

→ status가 등호(=) 조건이므로 첫 번째 컬럼
→ created_at은 ORDER BY이므로 두 번째 컬럼
→ 복합 인덱스로 커버링 가능
```

---

## 버그 발견 & 수정 — 비동기 콜백과 @Transactional

### 문제

```java
// Before (버그)
@Transactional
public void relayPendingEvents() {
    List<OutboxEvent> events = findPendingEvents();
    for (OutboxEvent event : events) {
        kafkaTemplate.send(...).whenComplete((result, ex) -> {
            event.markPublished();  // ❌ TX가 이미 커밋된 후에 실행!
        });
    }
}  // ← 여기서 TX 커밋. markPublished()는 아직 호출 안 됨.
```

**증상**: 같은 outboxId=1을 매 Polling마다 반복 발행. PENDING 상태 영구 유지.

**원인**:
- `whenComplete`는 비동기 콜백 → 별도 스레드에서 실행
- `@Transactional` TX 경계 = 메서드 리턴 시점
- 콜백 실행 시점에는 TX가 이미 커밋됨 → JPA Dirty Checking 작동 안 함

### 수정

```java
// After (수정)
@Transactional
public void relayPendingEvents() {
    List<OutboxEvent> events = findPendingEvents();
    for (OutboxEvent event : events) {
        SendResult result = kafkaTemplate.send(...).get(10, SECONDS);  // 동기 대기!
        event.markPublished();  // ✅ TX가 아직 살아있음
    }
}  // ← markPublished() 반영된 상태로 TX 커밋
```

### 트레이드오프

```
비동기 whenComplete              동기 .get()
─────────────────────            ─────────────────────
✅ Relay 빠름 (non-blocking)     ❌ Relay 느림 (blocking)
❌ TX 밖에서 상태 변경            ✅ TX 안에서 상태 변경
❌ markPublished 반영 안 됨       ✅ markPublished 정상 반영
❌ 무한 반복 발행 버그             ✅ 정확히 1번 발행 후 PUBLISHED

→ Relay는 "안전하게 발행"이 목적. 속도보다 정확성이 우선.
→ 동기 방식이 맞다.
```

---

## 실측 결과

### 기본 흐름 (1건)

```
시각        상태
─────────── ──────────
09:55:25    주문 생성 → Outbox PENDING (같은 TX)
09:55:25    API 응답: "orderId=25102, outbox=PENDING"
09:55:30    Relay 실행 → Kafka 발행 성공 → PUBLISHED
                                               ↑
                                          약 5초 지연 (Polling 간격)

DB 확인:
  id=2, topic=order.created.event-v1, partition_key=25102,
  status=PUBLISHED, retry_count=0,
  created_at=09:55:25, published_at=09:55:30
```

### 대량 흐름 (20건)

```
시각        PENDING    PUBLISHED
─────────── ──────── ──────────
즉시         20        1 (이전 1건)
10초 후       0        21

→ 20건 전부 PENDING → PUBLISHED 전환 완료
→ Relay가 BATCH_SIZE=50으로 한 번에 처리
```

---

## Relay 스케줄링 설계

```
┌───────────────────────┬──────────┬───────────────────────────────┐
│ 스케줄                 │ 간격     │ 용도                          │
├───────────────────────┼──────────┼───────────────────────────────┤
│ relayPendingEvents()  │ 5초      │ PENDING → Kafka 발행          │
│ retryFailedEvents()   │ 30초     │ FAILED(retry<5) → 재시도      │
│ cleanupPublishedEvents│ 1시간    │ 7일 이전 PUBLISHED 삭제       │
└───────────────────────┴──────────┴───────────────────────────────┘

재시도 전략:
  - 최대 5회 재시도
  - 5회 초과 시 FAILED 상태로 남김 → 수동 확인 또는 DLQ 전송
```

---

## at-least-once의 의미

```
Outbox Relay는 at-least-once를 보장한다.

시나리오: Relay가 Kafka 발행 성공 후, DB 업데이트 전에 죽으면?
  1. Kafka에 메시지 전송 성공 ✅
  2. event.markPublished() 호출 ✅
  3. TX 커밋 전에 앱 크래시 ❌
  4. DB에는 아직 PENDING 상태
  5. 앱 재시작 → Relay가 같은 이벤트를 다시 조회 → 다시 발행
  → 같은 메시지가 Kafka에 2번 발행됨!

→ 이래서 Consumer 쪽 멱등성이 반드시 필요하다 (Phase 5).
→ "최소 1번은 발행" 보장. "정확히 1번"은 보장하지 않음.
```

---

## 실험 API 정리

```bash
# Outbox 패턴으로 주문 생성
curl -X POST "http://localhost:8085/api/experiment/outbox/order?userId=1&amount=29900"

# 대량 주문 생성
curl -X POST http://localhost:8085/api/experiment/outbox/orders/bulk/20

# Outbox 상태 조회
curl http://localhost:8085/api/experiment/outbox/status

# Relay 수동 실행 (스케줄러 안 기다리고 즉시)
curl -X POST http://localhost:8085/api/experiment/outbox/relay/trigger

# DB 직접 확인
docker exec pipeline-mysql mysql -upipeline -ppipeline pipeline \
  -e "SELECT id,topic,partition_key,status,retry_count,created_at,published_at FROM outbox_event ORDER BY id DESC LIMIT 10;"

# 실험 가이드
curl http://localhost:8085/api/experiment/outbox/guide
```

---

## 프로젝트 구조 (Phase 4 추가분)

```
src/main/java/com/pipeline/
├── outbox/
│   ├── OutboxEvent.java              # Entity — 상태 머신 (PENDING→PUBLISHED→삭제)
│   ├── OutboxStatus.java             # Enum (PENDING, PUBLISHED, FAILED)
│   ├── OutboxEventRepository.java    # JPA Repository (Polling 쿼리)
│   ├── OutboxEventService.java       # 비즈니스 TX 안에서 Outbox 저장
│   └── OutboxRelayService.java       # 스케줄러 — Polling → Kafka 발행 (동기)
├── order/
│   └── OrderService.java             # 주문 생성 + Outbox 저장 (같은 TX)
└── api/
    └── OutboxExperimentController.java  # 실험 API
```

---

## 다음 단계: Phase 5 — 멱등성 처리

- at-least-once의 중복 발행 문제를 Consumer에서 해결
- event_handled 테이블로 이미 처리한 이벤트 필터링
- eventId (UUID) 기반 중복 감지
