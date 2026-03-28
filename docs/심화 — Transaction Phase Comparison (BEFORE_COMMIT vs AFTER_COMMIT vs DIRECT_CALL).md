# 심화 — Transaction Phase Comparison

## 개요

Phase 4(Transactional Outbox)를 구현하면서 발견한 핵심 질문:

> **"이벤트를 언제 발행하느냐"에 따라 시스템의 안전성이 완전히 달라진다.**

Spring의 `@TransactionalEventListener`는 트랜잭션 라이프사이클의 특정 시점에 코드를 끼워넣을 수 있다. 이 프로젝트에서는 3가지 방식을 직접 구현하고 동작 차이를 실측 비교했다.

---

## 3가지 이벤트 발행 방식

### A. BEFORE_COMMIT — 같은 트랜잭션 안에서 Outbox 저장

```
[Business TX 시작]
  │
  ├── 주문 저장 (OrderRepository.save)
  ├── ApplicationEventPublisher.publishEvent(ComparisonEvent)
  │       ↓
  │   @TransactionalEventListener(phase = BEFORE_COMMIT)
  │   → Outbox INSERT (같은 TX에 참여)
  │
[TX 커밋] → 주문 + Outbox가 함께 커밋
  │
[Relay가 나중에 Outbox → Kafka 발행]
```

**핵심**: Outbox INSERT가 비즈니스 TX 안에서 실행된다.
- 주문 저장 실패 → TX 롤백 → Outbox도 롤백 (안전)
- Outbox INSERT 실패 → TX 롤백 → 주문도 롤백 (안전)
- 양쪽이 **원자적(atomic)**으로 묶인다

### B. AFTER_COMMIT — 커밋 후 즉시 Kafka 발행 시도

```
[Business TX 시작]
  │
  ├── 주문 저장
  ├── Outbox INSERT (Facade가 직접 저장)
  │
[TX 커밋] → 주문 + Outbox가 함께 커밋
  │
  ↓ @TransactionalEventListener(phase = AFTER_COMMIT)
  → Kafka 즉시 발행 시도
  → 성공하면 Outbox.status = PUBLISHED
  → 실패하면 Outbox.status = PENDING (Relay가 나중에 재시도)
```

**핵심**: Kafka 발행이 TX 밖에서 실행된다.
- 커밋은 이미 됐으므로 Kafka 실패가 비즈니스 데이터에 영향 없음
- 즉시 발행 성공 시 Relay 대기 없이 빠르게 전달
- 하지만 AFTER_COMMIT 리스너 안에서의 **예외가 조용히 무시될 수 있다** (Spring 기본 동작)

### C. DIRECT_CALL — Facade에서 Outbox 직접 저장

```
[Business TX 시작]
  │
  ├── 주문 저장
  ├── OutboxEventService.save(outboxEvent) ← Facade가 직접 호출
  │
[TX 커밋] → 주문 + Outbox가 함께 커밋
  │
[Relay가 5초 간격으로 PENDING 이벤트를 Kafka에 발행]
```

**핵심**: Spring Event 메커니즘을 사용하지 않는다.
- 가장 명시적이고 디버깅이 쉬움
- 코드 흐름이 일직선 — 숨겨진 리스너가 없음
- 이 프로젝트의 Outbox 패턴이 이 방식

---

## 실패 시나리오 비교

### 비즈니스 로직 실패 시 (TX 롤백)

| 방식 | 주문 데이터 | Outbox | Kafka | 안전? |
|------|-----------|--------|-------|------|
| A. BEFORE_COMMIT | 롤백 | 롤백 (같은 TX) | 발행 안 됨 | **안전** |
| B. AFTER_COMMIT | 롤백 | 롤백 (같은 TX) | AFTER_COMMIT 안 호출됨 | **안전** |
| C. DIRECT_CALL | 롤백 | 롤백 (같은 TX) | 발행 안 됨 | **안전** |

→ 3가지 모두 **비즈니스 실패 시 안전**. Outbox가 비즈니스 TX 안에 있으니까.

### Kafka 발행 실패 시

| 방식 | 주문 데이터 | Outbox | Kafka | 복구 |
|------|-----------|--------|-------|------|
| A. BEFORE_COMMIT | 이미 커밋됨 | PENDING 유지 | 실패 | Relay가 재시도 |
| B. AFTER_COMMIT | 이미 커밋됨 | PENDING 유지 | 실패 | Relay가 재시도 |
| C. DIRECT_CALL | 이미 커밋됨 | PENDING 유지 | 실패 | Relay가 재시도 |

→ 3가지 모두 **Kafka 실패 시 Outbox에 남아있으므로 Relay가 복구 가능**.

### 진짜 차이가 드러나는 시나리오: AFTER_COMMIT 리스너 내부 예외

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onAfterCommit(ComparisonAfterCommitEvent event) {
    // 이 안에서 예외가 발생하면?
    kafkaTemplate.send(topic, key, payload).get(); // ← Kafka 실패
    // Spring은 이 예외를 로그만 남기고 삼킨다 (기본 동작)
    // 호출자에게 전파되지 않는다!
}
```

이것이 AFTER_COMMIT의 함정:
- 예외가 호출자에게 전파되지 않으므로 **실패를 감지하기 어렵다**
- Outbox status가 PENDING으로 남아있어서 Relay가 처리하긴 하지만
- "즉시 발행" 이점이 사라지고 결국 Relay 대기와 같아짐

---

## 실험 API

```bash
# 정상 시나리오
curl -X POST localhost:8085/api/comparison/before-commit/1    # A. BEFORE_COMMIT
curl -X POST localhost:8085/api/comparison/after-commit/2     # B. AFTER_COMMIT
curl -X POST localhost:8085/api/comparison/direct-call/3      # C. DIRECT_CALL

# 실패 시나리오 — TX 롤백 시 Outbox 상태 확인
curl -X POST localhost:8085/api/comparison/before-commit-fail/4
curl -X POST localhost:8085/api/comparison/direct-call-fail/5

# 응답 예시 — Outbox 상태 카운트
{
  "method": "BEFORE_COMMIT",
  "orderId": 1,
  "outboxPendingCount": 1,
  "outboxPublishedCount": 0,
  "outboxFailedCount": 0
}
```

---

## 최종 판단: 왜 DIRECT_CALL(C)을 선택했는가

| 기준 | A. BEFORE_COMMIT | B. AFTER_COMMIT | C. DIRECT_CALL |
|------|-----------------|-----------------|----------------|
| 원자성 | 같은 TX (안전) | 같은 TX (안전) | 같은 TX (안전) |
| 명시성 | 리스너에 숨겨짐 | 리스너에 숨겨짐 | 코드에 드러남 |
| 디버깅 | 이벤트 발행→리스너 추적 필요 | 이벤트 발행→리스너 추적 필요 | 일직선 흐름 |
| 실패 감지 | 가능 (TX 안이니까) | 어려움 (예외가 삼켜짐) | 가능 (TX 안이니까) |
| 복잡도 | Event + Listener 필요 | Event + Listener 필요 | Service 호출만 |
| 테스트 | 이벤트 발행 Mock 필요 | 이벤트 발행 Mock 필요 | 직접 호출 |

**결론**: 3가지 모두 원자성은 동일하게 보장된다. 차이는 **명시성과 디버깅 용이성**.

- BEFORE_COMMIT은 Spring Event 메커니즘 의존 → 코드만 보면 Outbox 저장이 어디서 일어나는지 안 보임
- AFTER_COMMIT은 예외 삼킴 함정이 있음
- DIRECT_CALL은 "Facade에서 주문 저장 → Outbox 저장"이 한 눈에 보임

**"가장 단순하고 명시적인 방식이 가장 안전하다"** — 이것이 이 프로젝트의 설계 판단.

---

## 관련 문서

- [Outbox Relay 동기 vs 비동기 vs CDC](Outbox%20Relay%20동기%20vs%20비동기%20vs%20CDC%20—%20실무%20판단%20기준.md)
- [@Transactional 경계 문제 심화](Outbox%20Relay의%20비동기%20vs%20동기%20—%20@Transactional%20경계%20문제%20심화.md)
- [Phase 4 설계](Phase%204%20—%20Transactional%20Outbox%20패턴%20구현.md)
