# Phase 5 — 멱등성 처리 (event_handled + 같은 TX)

---

## 해결하는 문제

```
at-least-once의 중복 발행:

  Outbox Relay가 Kafka에 발행 성공 → DB 업데이트 전 앱 크래시
  → 앱 재시작 → 같은 이벤트를 다시 발행
  → Consumer가 같은 메시지를 2번 받음

  또는:
  Consumer가 처리 완료 → ACK 전 앱 크래시
  → 앱 재시작 → 같은 메시지를 다시 받음

  → 같은 주문을 2번 처리? 같은 쿠폰을 2번 발급? 같은 포인트를 2번 적립?
  → 멱등성 처리가 필수.
```

---

## 설계: DB event_handled (케브님 권장 방식)

### 왜 DB인가? (Redis가 아닌 이유)

```
Redis SET NX의 문제 (1팀 김대진 질문, 케브님 답변):

  ① SET NX "event-123" → 성공 (처리 시작 표시)
  ② 비즈니스 로직 실행... 실패! ❌
  ③ Redis에는 "event-123 = 처리됨"으로 남아있음
  ④ 재처리 시도 → SET NX 실패 → 이벤트가 영원히 처리 안 됨!

  DEL로 롤백?
  → 네트워크 장애로 DEL도 실패할 수 있음
  → Redis와 DB의 상태가 불일치


DB event_handled + 같은 TX:

  @Transactional {
    ① 비즈니스 로직 실행
    ② event_handled INSERT
  }  // TX 커밋 → 둘 다 확정
     // TX 롤백 → 둘 다 취소

  → 비즈니스 실패 = 멱등 기록도 롤백 → 재처리 가능
  → Redis에서 불가능한 "원자적 롤백"이 DB TX에서는 자연스러움
```

### event_handled 테이블

```sql
CREATE TABLE event_handled (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    VARCHAR(100) NOT NULL UNIQUE,    -- Producer가 생성한 UUID
    event_type  VARCHAR(100) NOT NULL,           -- ORDER_CREATED 등
    handled_at  DATETIME(6) NOT NULL,

    UNIQUE INDEX idx_event_handled_event_id (event_id)
);
```

### Consumer 처리 흐름

```
메시지 수신 (eventId="abc-123")
     │
     ▼
event_handled에 "abc-123" 존재?
     │
     ├── YES → 중복! ACK만 하고 스킵
     │         (offset은 전진시킴 → 다시 안 받음)
     │
     └── NO → @Transactional 시작
              │
              ├── ① 비즈니스 로직 (재고 차감, 포인트 적립...)
              ├── ② event_handled INSERT ("abc-123")
              └── TX 커밋
              │
              ▼
         ACK (offset 커밋)
```

---

## 실측 결과

### 실험 1: 같은 eventId 2번 발행

```
발행: duplicate-test-77777 × 2건

Consumer 로그:
  offset=1209  수신 → 주문 처리 중 → ACK 완료
  offset=1210  수신 → 중복 메시지 스킵 ✅

event_handled 테이블:
  id=1, event_id=duplicate-test-77777, type=ORDER_CREATED

→ 2번 발행, 1번만 처리, 1건만 DB 기록
```

### 실험 2: 같은 eventId 10번 발행 (Flood)

```
발행: flood-test-88888 × 10건

Consumer 로그:
  offset=1135  수신 → 주문 처리 중 → ACK 완료
  offset=1136  수신 → 중복 메시지 스킵
  offset=1137  수신 → 중복 메시지 스킵
  offset=1138  수신 → 중복 메시지 스킵
  offset=1139  수신 → 중복 메시지 스킵
  offset=1140  수신 → 중복 메시지 스킵
  offset=1141  수신 → 중복 메시지 스킵
  offset=1142  수신 → 중복 메시지 스킵
  offset=1143  수신 → 중복 메시지 스킵
  offset=1144  수신 → 중복 메시지 스킵

event_handled 테이블:
  id=2, event_id=flood-test-88888, type=ORDER_CREATED

→ 10번 발행, 1번만 처리, 9번 중복 스킵 ✅
```

---

## 동시성 안전장치: UNIQUE 제약 조건

```
시나리오: Consumer 2개가 동시에 같은 eventId를 처리하려고 하면?

  Consumer A                          Consumer B
  ─────────                          ─────────
  isAlreadyHandled? → NO             isAlreadyHandled? → NO
       │                                  │
       ▼                                  ▼
  비즈니스 로직 실행                  비즈니스 로직 실행
       │                                  │
       ▼                                  ▼
  INSERT event_handled ✅            INSERT event_handled ❌
  (성공)                              (UNIQUE 위반 → DataIntegrityViolation)
       │                                  │
       ▼                                  ▼
  TX 커밋                            TX 롤백 → 비즈니스도 롤백
       │                                  │
       ▼                                  ▼
  ACK                                 다음 poll에서 → isAlreadyHandled → YES → 스킵

→ UNIQUE 제약 조건이 DB 레벨에서 동시성을 방지한다.
→ 2개 Consumer가 동시에 시작해도 1개만 성공한다.
```

---

## Outbox → 멱등성 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Phase 4: Outbox (Producer 측)                                          │
│                                                                         │
│  @Transactional {                                                       │
│    order 저장 + outbox 저장 (같은 TX)                                  │
│  }                                                                      │
│       │                                                                 │
│       │  Relay (5초 Polling, 동기 .get())                              │
│       ▼                                                                 │
│  Kafka에 발행 (eventId = UUID 포함)                                    │
│       │                                                                 │
│       │  at-least-once (중복 발행 가능)                                │
│       ▼                                                                 │
│  Phase 5: 멱등성 (Consumer 측)                                         │
│                                                                         │
│  Consumer 수신 → eventId로 중복 체크                                   │
│       │                                                                 │
│       ├── 중복 → 스킵 + ACK                                           │
│       │                                                                 │
│       └── 신규 → @Transactional {                                      │
│                     비즈니스 로직 + event_handled INSERT (같은 TX)     │
│                  }                                                      │
│                  → ACK                                                  │
│                                                                         │
│  결과: Producer at-least-once + Consumer 멱등성 = 사실상 exactly-once  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## event_handled 테이블 운영 (케브님 답변 기반)

```
Q: 테이블이 계속 쌓이는데 언제 지우나? (3팀 이도현 질문)

A:
  1. Kafka retention period보다 길게 보관
     → Kafka 7일이면 event_handled는 최소 7일 이상
     → Kafka에 메시지가 남아있는 동안은 재처리 가능성 있으므로

  2. 보관 기간 지나면 배치 삭제
     → @Scheduled로 7일 이전 레코드 삭제

  3. 대용량이면 파티션 테이블
     → 날짜별 파티션 → DROP PARTITION으로 빠르게 삭제
```

---

## 실험 API 정리

```bash
# 같은 eventId로 2번 발행 → 중복 감지 확인
curl -X POST http://localhost:8085/api/experiment/idempotency/duplicate-test/77777

# 같은 eventId로 10번 발행 → 1건만 처리 확인
curl -X POST "http://localhost:8085/api/experiment/idempotency/flood/88888?count=10"

# event_handled 테이블 상태 조회
curl http://localhost:8085/api/experiment/idempotency/status

# DB 직접 확인
docker exec pipeline-mysql mysql -upipeline -ppipeline pipeline \
  -e "SELECT * FROM event_handled ORDER BY id DESC LIMIT 10;"
```

---

## 프로젝트 구조 (Phase 5 추가분)

```
src/main/java/com/pipeline/
├── idempotency/
│   ├── EventHandled.java              # Entity — eventId UNIQUE
│   ├── EventHandledRepository.java    # JPA Repository
│   └── IdempotencyService.java        # 멱등성 체크 + 기록
├── consumer/
│   └── OrderEventConsumer.java        # 멱등성 적용 (isAlreadyHandled → processWithIdempotency)
└── api/
    └── IdempotencyExperimentController.java  # 중복 발행 실험 API
```

---

## 다음 단계: Phase 6 — DLQ + 에러 핸들링

- 처리 실패 메시지를 DLQ 토픽으로 격리
- 재시도 전략 (몇 번 재시도 후 DLQ)
- DLQ 메시지 모니터링 및 후처리
