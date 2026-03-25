# Phase 7 — 실전 선착순 쿠폰 발급 (동시성 테스트 + 전체 통합)

---

## Phase 1~6 통합 시나리오

```
이 Phase에서 사용하는 기법:

  Phase 1: 3-Broker 클러스터 → 1대 죽어도 유실 없음
  Phase 2: key=couponId → 같은 쿠폰의 요청은 같은 파티션 → 순차 처리
  Phase 3: Manual ACK → 처리 완료 후에만 offset 커밋
  Phase 4: (Outbox는 이 실험에서는 직접 발행으로 대체 — 동시성에 집중)
  Phase 5: 멱등성 → event_handled + 같은 TX → 중복 발급 방지
  Phase 6: 3회 재시도 + 지수 백오프 + DLQ → 실패 메시지 격리
```

---

## 핵심: couponId가 key인 이유

```
┌─────────────────────────────────────────────────────────────────────┐
│  ❌ userId를 key로 쓰면?                                            │
│                                                                     │
│  유저A(coupon-1) ──→ Partition 0  ┐                                │
│  유저B(coupon-1) ──→ Partition 2  ┤  같은 쿠폰인데 다른 파티션!     │
│  유저C(coupon-1) ──→ Partition 1  ┘  → 3개 Consumer가 동시에       │
│                                       재고 체크 → 초과 발급!       │
│                                                                     │
│  ✅ couponId를 key로 쓰면?                                          │
│                                                                     │
│  유저A(coupon-1) ──→ Partition 0  ┐                                │
│  유저B(coupon-1) ──→ Partition 0  ┤  같은 쿠폰 = 같은 파티션!      │
│  유저C(coupon-1) ──→ Partition 0  ┘  → 1 Consumer가 순차 처리     │
│                                       → 재고 체크가 직렬화됨       │
│                                       → 초과 발급 방지!            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 실측 결과

### 테스트 1: 200명 동시 요청, 쿠폰 100장

```
조건:
  - couponId=1, totalQuantity=100
  - 200명의 유저가 50스레드로 동시에 Kafka에 발급 요청
  - key=couponId → 같은 파티션으로 전부 라우팅

발행:
  200건 발행 완료: 247ms (50스레드 병렬)

결과:
  ┌─────────────────────┬──────────┐
  │ totalQuantity       │ 100      │
  │ issuedQuantity      │ 100      │  ← 정확히 100장
  │ remainingQuantity   │ 0        │
  │ issuedLogCount      │ 100      │  ← DB 이력도 100건
  │ 정합성               │ ✅ 일치  │
  │ 초과발급             │ ✅ 정상  │
  └─────────────────────┴──────────┘

Consumer 로그 분석:
  발급 성공 로그: 200건 (CouponIssueService + CouponIssueConsumer 각 100건)
  재고 소진 로그: 200건 (100명은 거부)

  마지막 발급: userId=82, remaining=0
  첫 재고 소진: userId=80, issued=100/100

→ 200명 요청, 100명 발급, 100명 재고 소진 거부
→ 초과 발급 0건 ✅
```

### 테스트 2: 1000명 동시 요청, 쿠폰 100장

```
조건:
  - couponId=2, totalQuantity=100
  - 1000명의 유저가 100스레드로 동시에 요청

발행:
  1000건 발행 완료: 65ms (100스레드 병렬)

결과:
  ┌─────────────────────┬──────────┐
  │ totalQuantity       │ 100      │
  │ issuedQuantity      │ 100      │  ← 정확히 100장
  │ remainingQuantity   │ 0        │
  │ issuedLogCount      │ 100      │  ← DB 이력도 100건
  │ 정합성               │ ✅ 일치  │
  │ 초과발급             │ ✅ 정상  │
  └─────────────────────┴──────────┘

→ 1000명 요청, 100명 발급, 900명 거부
→ 초과 발급 0건 ✅
→ 발행 속도: 1000건/65ms = 초당 약 15,000건
```

---

## 왜 초과 발급이 안 되는가 — 동시성 제어 구조

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  200명의 유저가 동시에 요청                                         │
│       │                                                             │
│       ▼                                                             │
│  50스레드가 병렬로 Kafka에 발행                                     │
│  key = couponId = 1 (전부 동일)                                    │
│       │                                                             │
│       ▼                                                             │
│  murmur2("1") % 3 → Partition 0 (전부 같은 파티션!)                │
│       │                                                             │
│       ▼                                                             │
│  Partition 0의 Consumer 1개가 순차 처리                             │
│  ┌─────────────────────────────────────────┐                       │
│  │  offset=0: userId=1 → 재고 100 → 발급 ✅ │                       │
│  │  offset=1: userId=2 → 재고 99  → 발급 ✅ │                       │
│  │  ...                                     │                       │
│  │  offset=99: userId=100 → 재고 1 → 발급 ✅│                       │
│  │  offset=100: userId=101 → 재고 0 → 거부 ❌│                       │
│  │  offset=101: userId=102 → 재고 0 → 거부 ❌│                       │
│  │  ...                                     │                       │
│  └─────────────────────────────────────────┘                       │
│                                                                     │
│  핵심: 같은 파티션 = 1 Consumer = 순차 처리                        │
│  → DB 재고 체크가 직렬화됨                                         │
│  → 동시에 2명이 재고를 체크하는 상황이 발생하지 않음                │
│  → Lock 없이도 동시성 제어 가능!                                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Lock 없는 동시성 제어의 조건

```
이 구조가 동작하려면:

  1. 같은 쿠폰의 모든 요청이 같은 파티션에 가야 한다
     → key = couponId ✅

  2. 같은 파티션을 1개 Consumer만 읽어야 한다
     → Consumer Group 내 1 Partition : 1 Consumer ✅

  3. Consumer가 순차 처리해야 한다
     → Manual ACK + 단건 처리 ✅

  하나라도 깨지면?
  - key가 없으면 → Round-Robin → 다른 파티션 → 동시 접근 → 초과 발급!
  - Batch 처리에서 병렬 처리하면 → 같은 파티션 내에서도 동시 접근 가능
  - concurrency > partitions면 → 의미 없는 Consumer 발생 (문제는 아님)
```

---

## 실험 API 정리

```bash
# 쿠폰 생성
curl -X POST "http://localhost:8085/api/experiment/coupon/create?couponId=1&name=welcome-coupon&quantity=100"

# 동시성 테스트 (200명, 50스레드)
curl -X POST "http://localhost:8085/api/experiment/coupon/concurrent-test?couponId=1&userCount=200&threads=50"

# 결과 확인
curl "http://localhost:8085/api/experiment/coupon/result?couponId=1"

# 재고 확인
curl "http://localhost:8085/api/experiment/coupon/stock?couponId=1"
```

---

## 프로젝트 구조 (Phase 7 추가분)

```
src/main/java/com/pipeline/
├── coupon/
│   ├── CouponEvent.java               # 쿠폰 발급 요청 이벤트
│   ├── CouponStock.java               # 쿠폰 재고 Entity (tryIssue)
│   ├── CouponIssuedLog.java           # 발급 이력 Entity (1인 1쿠폰)
│   ├── CouponStockRepository.java
│   ├── CouponIssuedLogRepository.java
│   ├── CouponIssueService.java        # 발급 서비스 (재고+이력+멱등 같은 TX)
│   └── CouponIssueConsumer.java       # Consumer (멱등+재시도+DLQ 통합)
└── api/
    └── CouponExperimentController.java # 동시성 테스트 API
```

---

## Phase 1~7 전체 파이프라인 완성

```
┌──────────────────────────────────────────────────────────────────────┐
│  API 요청 (200명 동시)                                               │
│       │                                                              │
│       ▼  50스레드 병렬 발행 (247ms)                                  │
│                                                                      │
│  Phase 2: Producer                                                   │
│  key=couponId → acks=all + idempotence=true                         │
│       │                                                              │
│       ▼                                                              │
│  Phase 1: Kafka Cluster (3 Broker, Replication=3, Min ISR=2)        │
│  coupon.issue.request-v1 토픽, Partition 0에 전부 적재              │
│       │                                                              │
│       ▼                                                              │
│  Phase 3: Consumer (Manual ACK, concurrency=3)                      │
│  Partition 0의 Consumer가 순차 처리                                  │
│       │                                                              │
│       ├── Phase 5: 멱등성 체크 (event_handled)                      │
│       │   → 중복이면 스킵 + ACK                                    │
│       │                                                              │
│       ├── Phase 7: 비즈니스 로직                                    │
│       │   → @Transactional { 재고 체크 + 차감 + 이력 저장 + 멱등 기록 }│
│       │   → 재고 0이면 거부                                         │
│       │                                                              │
│       ├── Phase 6: 실패 시 재시도 3회 + DLQ                         │
│       │                                                              │
│       └── ACK (offset 커밋)                                         │
│                                                                      │
│  결과: 200명 요청 → 100명 발급 → 100명 거부 → 초과 발급 0건         │
└──────────────────────────────────────────────────────────────────────┘
```
