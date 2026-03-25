# Phase 7 실험 기록 — 선착순 쿠폰 동시성 테스트 실측 상세

---

## 실험 환경

```
인프라: 3-Broker KRaft 클러스터
토픽: coupon.issue.request-v1 (partition 3, replicas 3)
Consumer Group: coupon-issue-group (concurrency=3, Manual ACK)
DB: pipeline-mysql (coupon_stock + coupon_issued_log + event_handled)
멱등성: event_handled (UNIQUE eventId)
재시도: 최대 3회, 지수 백오프
DLQ: pipeline.dlq-v1
```

---

## 테스트 1: 200명 동시 요청, 쿠폰 100장

### 준비

```bash
# 쿠폰 생성
curl -s -X POST "http://localhost:8085/api/experiment/coupon/create?couponId=1&name=welcome-coupon&quantity=100"
```

```json
{
    "couponId": 1,
    "name": "welcome-coupon",
    "totalQuantity": 100,
    "issuedQuantity": 0
}
```

### 실행

```bash
curl -s -X POST "http://localhost:8085/api/experiment/coupon/concurrent-test?couponId=1&userCount=200&threads=50"
```

```json
{
    "couponId": 1,
    "userCount": 200,
    "threads": 50,
    "published": 200,
    "publishTimeMs": 247
}
```

### 발행 분석

```
200건 발행: 247ms (50스레드 병렬)
초당 발행: 200 / 0.247 = 약 810건/초

key = couponId = "1" (200건 모두 동일)
→ murmur2("1") % 3 → 전부 같은 파티션(Partition 0)에 적재
→ offset 0~199로 순차 적재
```

### 결과

```bash
curl -s "http://localhost:8085/api/experiment/coupon/result?couponId=1"
```

```json
{
    "couponId": 1,
    "couponName": "welcome-coupon",
    "totalQuantity": 100,
    "issuedQuantity": 100,
    "remainingQuantity": 0,
    "issuedLogCount": 100,
    "정합성": "✅ 일치",
    "초과발급": "✅ 정상"
}
```

### Consumer 로그 분석

```
발급 성공 로그 (CouponIssueService): 100건
발급 성공 로그 (CouponIssueConsumer): 100건
재고 소진 로그 (CouponIssueService): 100건
재고 소진 로그 (CouponIssueConsumer): 100건

마지막 발급 성공:
  13:22:20.189  [CouponService] 발급 성공 — couponId=1, userId=82, remaining=0
  13:22:20.198  [CouponConsumer] 발급 성공 — couponId=1, userId=82

첫 번째 재고 소진:
  13:22:20.204  [CouponService] 재고 소진 — couponId=1, userId=80, issued=100/100
  13:22:20.206  [CouponConsumer] 재고 소진 — couponId=1, userId=80

→ userId=82가 100번째 발급 (remaining=0)
→ userId=80이 101번째 요청 → 재고 소진 거부
→ 발급 순서는 Kafka 파티션 내 offset 순서 (요청 도착 순)
```

### 시간 흐름

```
13:22:18.xxx  200건 발행 완료 (247ms)
13:22:18.xxx  Consumer 처리 시작 (Partition 0에서 순차)
     │
     │  offset 0~99: 발급 성공 (100건)
     │  건당 약 20ms (재고 체크 + 차감 + 이력 저장 + 멱등 기록 + TX 커밋)
     │  총 약 2초
     │
13:22:20.189  100번째 발급 완료 (remaining=0)
13:22:20.204  101번째 요청 → 재고 소진 거부
     │
     │  offset 100~199: 재고 소진 거부 (100건)
     │  건당 약 5ms (재고 체크만 → 거부 → ACK)
     │  총 약 0.5초
     │
13:22:20.xxx  200건 전부 처리 완료

전체 처리 시간: 약 2.5초
```

---

## 테스트 2: 1000명 동시 요청, 쿠폰 100장

### 준비 + 실행

```bash
curl -s -X POST "http://localhost:8085/api/experiment/coupon/create?couponId=2&name=mega-sale-coupon&quantity=100"
curl -s -X POST "http://localhost:8085/api/experiment/coupon/concurrent-test?couponId=2&userCount=1000&threads=100"
```

```json
{
    "couponId": 2,
    "userCount": 1000,
    "threads": 100,
    "published": 1000,
    "publishTimeMs": 65
}
```

### 발행 분석

```
1000건 발행: 65ms (100스레드 병렬)
초당 발행: 1000 / 0.065 = 약 15,384건/초

→ 200명 테스트(810건/초)보다 스레드 수가 2배(50→100)라 발행 속도 19배 향상
→ KafkaTemplate 비동기 send()의 배치 최적화 효과
```

### 결과

```json
{
    "couponId": 2,
    "couponName": "mega-sale-coupon",
    "totalQuantity": 100,
    "issuedQuantity": 100,
    "remainingQuantity": 0,
    "issuedLogCount": 100,
    "정합성": "✅ 일치",
    "초과발급": "✅ 정상"
}
```

### 정합성 검증

```
┌─────────────────────┬──────────────┬──────────────┐
│ 검증 항목            │ 테스트 1      │ 테스트 2      │
├─────────────────────┼──────────────┼──────────────┤
│ 요청 수              │ 200          │ 1000         │
│ 쿠폰 수량            │ 100          │ 100          │
│ 발급 수              │ 100          │ 100          │  ← 정확히 100
│ 거부 수              │ 100          │ 900          │
│ issuedLogCount      │ 100          │ 100          │  ← DB 이력 일치
│ 초과 발급            │ ✅ 0건       │ ✅ 0건       │
│ 정합성               │ ✅ 일치      │ ✅ 일치      │
│ 발행 시간            │ 247ms        │ 65ms         │
│ 스레드 수            │ 50           │ 100          │
└─────────────────────┴──────────────┴──────────────┘
```

---

## DB 상태 확인

```sql
-- coupon_stock
SELECT * FROM coupon_stock;
+----------+------------------+----------------+----------------+
| coupon_id| coupon_name      | total_quantity | issued_quantity |
+----------+------------------+----------------+----------------+
| 1        | welcome-coupon   | 100            | 100            |
| 2        | mega-sale-coupon | 100            | 100            |
+----------+------------------+----------------+----------------+

-- coupon_issued_log (couponId=2 기준)
SELECT COUNT(*) FROM coupon_issued_log WHERE coupon_id = 2;
+----------+
| COUNT(*) |
+----------+
| 100      |
+----------+

-- event_handled (couponId=2 관련)
SELECT COUNT(*) FROM event_handled WHERE event_type = 'COUPON_ISSUED';
→ 200건 (테스트 1의 100건 + 테스트 2의 100건)
```

---

## 동시성 제어 구조 시퀀스

```
Thread-1 ─┐
Thread-2 ─┤  50~100 스레드가
Thread-3 ─┤  동시에 Kafka에
...       ─┤  send()
Thread-50 ─┘
     │
     ▼
  KafkaTemplate.send("coupon.issue.request-v1", key="1", event)
     │
     ▼  key="1" → murmur2("1") % 3 → Partition 0
     │
     ▼
  ┌──────────────────────────────────────────────────┐
  │  Partition 0                                      │
  │  [offset=0][offset=1][offset=2]...[offset=199]   │
  │  200건이 순차적으로 적재됨                         │
  └────────────────────────┬─────────────────────────┘
                           │
                      Consumer-1 (1개만)
                           │
                      순차 처리
                           │
  ┌────────────────────────┼─────────────────────────┐
  │                        ▼                          │
  │  offset=0:  재고 100 → tryIssue() → 99 → ✅      │
  │  offset=1:  재고 99  → tryIssue() → 98 → ✅      │
  │  ...                                              │
  │  offset=99: 재고 1   → tryIssue() → 0  → ✅      │
  │  offset=100:재고 0   → tryIssue() → false → ❌   │
  │  offset=101:재고 0   → tryIssue() → false → ❌   │
  │  ...                                              │
  │  offset=199:재고 0   → tryIssue() → false → ❌   │
  └───────────────────────────────────────────────────┘
                           │
                      결과: 100명 발급, 100명 거부
                      초과 발급: 0건
```

---

## 성능 관찰

```
발행 성능:
  200건/50스레드 = 247ms → 810건/초
  1000건/100스레드 = 65ms → 15,384건/초

Consumer 처리 성능:
  발급 1건: ~20ms (SELECT + UPDATE + INSERT×2 + TX COMMIT + ACK)
  거부 1건: ~5ms (SELECT → 재고 0 → ACK)

  100건 발급 + 100건 거부 = 약 2.5초
  100건 발급 + 900건 거부 = 약 6.5초

병목:
  - 발행(Producer)은 비동기라 매우 빠름
  - 처리(Consumer)는 단일 Consumer 순차 처리라 선형 증가
  - 처리량 향상이 필요하면? → 쿠폰 종류별 파티션 분리
    (couponId=1 → Partition 0, couponId=2 → Partition 1 → 다른 Consumer가 병렬 처리)
```
