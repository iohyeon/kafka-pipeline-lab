# Phase 7 실험 로그 — 선착순 쿠폰 전체 로그 + DB 상태 + Kafka 상태

---

## 테스트 1: 200명 동시 요청, 쿠폰 100장 (couponId=1)

### Consumer 수신 로그 (처음 5건 — offset 순)

```
13:22:17.631  [CouponConsumer] 수신 — partition=0, offset=0, couponId=1, userId=13,  eventId=f19a4eae-...
13:22:17.848  [CouponConsumer] 수신 — partition=0, offset=1, couponId=1, userId=31,  eventId=dde019a3-...
13:22:17.894  [CouponConsumer] 수신 — partition=0, offset=2, couponId=1, userId=47,  eventId=2d1cb8c4-...
13:22:17.931  [CouponConsumer] 수신 — partition=0, offset=3, couponId=1, userId=23,  eventId=adb17245-...
13:22:17.960  [CouponConsumer] 수신 — partition=0, offset=4, couponId=1, userId=5,   eventId=7f9e7cdf-...

→ 전부 partition=0 — couponId="1"이 key이므로 같은 파티션
→ offset 0부터 순차 증가
→ userId 순서가 13, 31, 47, 23, 5 — 요청 도착 순 (스레드 경합 결과)
→ 전부 같은 스레드(ntainer#5-0-C-1)에서 처리 — 1 Consumer가 순차 처리
```

### 발급 성공 로그 (처음 5건 — remaining 감소 관찰)

```
13:22:17.817  [CouponService] 발급 성공 — couponId=1, userId=13,  remaining=99
13:22:17.871  [CouponService] 발급 성공 — couponId=1, userId=31,  remaining=98
13:22:17.918  [CouponService] 발급 성공 — couponId=1, userId=47,  remaining=97
13:22:17.950  [CouponService] 발급 성공 — couponId=1, userId=23,  remaining=96
13:22:17.984  [CouponService] 발급 성공 — couponId=1, userId=5,   remaining=95

→ remaining이 99, 98, 97, 96, 95로 정확히 1씩 감소
→ 순차 처리가 보장되므로 동시에 2명이 같은 remaining을 보는 상황 없음
```

### 발급 성공 로그 (마지막 5건 — remaining=0 도달)

```
13:22:20.114  [CouponService] 발급 성공 — couponId=1, userId=144, remaining=4
13:22:20.135  [CouponService] 발급 성공 — couponId=1, userId=86,  remaining=3
13:22:20.153  [CouponService] 발급 성공 — couponId=1, userId=85,  remaining=2
13:22:20.172  [CouponService] 발급 성공 — couponId=1, userId=83,  remaining=1
13:22:20.189  [CouponService] 발급 성공 — couponId=1, userId=82,  remaining=0  ← 100번째 (마지막)

→ userId=82가 100번째 발급
→ 13:22:17.817 ~ 13:22:20.189 = 약 2.37초에 100건 처리
→ 건당 약 23.7ms
```

### 재고 소진 로그 (처음 5건 — 101번째부터 거부)

```
13:22:20.204  [CouponService] 재고 소진 — couponId=1, userId=80,  issued=100/100
13:22:20.213  [CouponService] 재고 소진 — couponId=1, userId=79,  issued=100/100
13:22:20.222  [CouponService] 재고 소진 — couponId=1, userId=78,  issued=100/100
13:22:20.233  [CouponService] 재고 소진 — couponId=1, userId=77,  issued=100/100
13:22:20.243  [CouponService] 재고 소진 — couponId=1, userId=75,  issued=100/100

→ userId=80이 101번째 요청 → issued=100/100 → 거부
→ 거부 건은 DB 쓰기 없이 빠르게 처리 (건당 약 10ms)
```

### 테스트 1 건수 요약

```
┌──────────────────────────┬───────┐
│ CouponConsumer 수신       │ 200건 │
│ CouponService 발급 성공   │ 100건 │
│ CouponConsumer 발급 성공  │ 100건 │
│ CouponService 재고 소진   │ 100건 │
│ CouponConsumer 재고 소진  │ 100건 │
│ DLQ 전송                  │   0건 │
│ 중복 스킵                  │   0건 │
└──────────────────────────┴───────┘

→ 200건 수신, 100건 발급, 100건 거부, 초과 발급 0건
```

---

## 테스트 2: 1000명 동시 요청, 쿠폰 100장 (couponId=2)

### 발급 성공 로그 (처음 5건)

```
13:25:58.286  [CouponService] 발급 성공 — couponId=2, userId=8,   remaining=99
13:25:58.309  [CouponService] 발급 성공 — couponId=2, userId=9,   remaining=98
13:25:58.333  [CouponService] 발급 성공 — couponId=2, userId=14,  remaining=97
13:25:58.356  [CouponService] 발급 성공 — couponId=2, userId=13,  remaining=96
13:25:58.372  [CouponService] 발급 성공 — couponId=2, userId=12,  remaining=95

→ 테스트 2는 partition=2에서 처리됨 (스레드: ntainer#5-2-C-1)
→ couponId=2의 murmur2 해시가 Partition 2로 라우팅
→ 테스트 1(Partition 0)과 다른 파티션이지만 동작은 동일
```

### 발급 성공 로그 (마지막 5건)

```
13:26:02.984  [CouponService] 발급 성공 — couponId=2, userId=96,  remaining=4
13:26:03.040  [CouponService] 발급 성공 — couponId=2, userId=97,  remaining=3
13:26:03.083  [CouponService] 발급 성공 — couponId=2, userId=98,  remaining=2
13:26:03.132  [CouponService] 발급 성공 — couponId=2, userId=99,  remaining=1
13:26:03.302  [CouponService] 발급 성공 — couponId=2, userId=105, remaining=0  ← 100번째

→ userId=105가 100번째 발급
→ 13:25:58.286 ~ 13:26:03.302 = 약 5.02초에 100건 처리
→ 건당 약 50.2ms (테스트 1보다 느림 — 테스트 1의 event_handled가 쌓여서 SELECT 비용 증가)
```

### 재고 소진 로그 (처음 5건)

```
13:26:03.379  [CouponService] 재고 소진 — couponId=2, userId=102, issued=100/100
13:26:03.417  [CouponService] 재고 소진 — couponId=2, userId=101, issued=100/100
13:26:03.549  [CouponService] 재고 소진 — couponId=2, userId=107, issued=100/100
13:26:03.585  [CouponService] 재고 소진 — couponId=2, userId=108, issued=100/100
13:26:03.615  [CouponService] 재고 소진 — couponId=2, userId=110, issued=100/100

→ userId=102가 101번째 → 거부
→ 900건 거부 처리
```

### 테스트 2 건수 요약

```
┌──────────────────────────┬────────┐
│ CouponConsumer 수신       │ 1000건 │
│ CouponService 발급 성공   │  100건 │
│ CouponConsumer 발급 성공  │  100건 │
│ CouponService 재고 소진   │  900건 │
│ CouponConsumer 재고 소진  │  900건 │
│ DLQ 전송                  │    0건 │
│ 중복 스킵                  │    0건 │
└──────────────────────────┴────────┘

→ 1000건 수신, 100건 발급, 900건 거부, 초과 발급 0건
```

---

## DB 상태 — 실험 후 스냅샷

### coupon_stock 테이블

```sql
mysql> SELECT * FROM coupon_stock;

+-----------+------------------+-----------------+----------------+
| coupon_id | coupon_name      | issued_quantity | total_quantity |
+-----------+------------------+-----------------+----------------+
|         1 | welcome-coupon   |             100 |            100 |
|         2 | mega-sale-coupon |             100 |            100 |
+-----------+------------------+-----------------+----------------+

→ 두 쿠폰 모두 issued_quantity = total_quantity = 100
→ 초과 발급 없음 ✅
```

### coupon_issued_log 건수

```sql
mysql> SELECT coupon_id, COUNT(*) as issued_count FROM coupon_issued_log GROUP BY coupon_id;

+-----------+--------------+
| coupon_id | issued_count |
+-----------+--------------+
|         1 |          100 |
|         2 |          100 |
+-----------+--------------+

→ 이력 건수 = issued_quantity → 정합성 일치 ✅
```

### coupon_issued_log 샘플 (couponId=1, 처음 5건)

```sql
mysql> SELECT * FROM coupon_issued_log WHERE coupon_id=1 ORDER BY id LIMIT 5;

+----+-----------+--------------------------------------+----------------------------+---------+
| id | coupon_id | event_id                             | issued_at                  | user_id |
+----+-----------+--------------------------------------+----------------------------+---------+
|  1 |         1 | f19a4eae-db37-437b-805a-c5033bfafdf3 | 2026-03-25 13:22:17.780346 |      13 |
|  2 |         1 | dde019a3-d0c7-4740-afc8-2d0e7d3d2f79 | 2026-03-25 13:22:17.864049 |      31 |
|  3 |         1 | 2d1cb8c4-16ca-40b7-b743-4f5e86e0bb15 | 2026-03-25 13:22:17.908807 |      47 |
|  4 |         1 | adb17245-9bdc-4c0e-a984-d4dffabc6586 | 2026-03-25 13:22:17.943671 |      23 |
|  5 |         1 | 7f9e7cdf-ca6d-41b2-bdbf-b89816e4481f | 2026-03-25 13:22:17.971729 |       5 |
+----+-----------+--------------------------------------+----------------------------+---------+

→ id 순서 = Kafka offset 순서 = 발급 순서
→ userId 순서(13, 31, 47, 23, 5)는 스레드 경합 결과 (요청 도착 순)
→ event_id가 각각 고유 UUID
```

### coupon_issued_log 샘플 (couponId=1, 마지막 5건)

```sql
mysql> SELECT * FROM coupon_issued_log WHERE coupon_id=1 ORDER BY id DESC LIMIT 5;

+-----+-----------+--------------------------------------+----------------------------+---------+
| id  | coupon_id | event_id                             | issued_at                  | user_id |
+-----+-----------+--------------------------------------+----------------------------+---------+
| 100 |         1 | ba6c525f-419d-4afa-90ec-50eac95ae38b | 2026-03-25 13:22:20.186312 |      82 |
|  99 |         1 | 244b7797-6ca9-462d-94c5-ac04d5e7042e | 2026-03-25 13:22:20.168420 |      83 |
|  98 |         1 | 87772f7c-058c-4de3-a75c-7a167ea76df7 | 2026-03-25 13:22:20.150737 |      85 |
|  97 |         1 | 8c64da6d-b43a-4623-ac3d-08d5dd0b9c8e | 2026-03-25 13:22:20.131231 |      86 |
|  96 |         1 | eecbd76f-1ccf-472a-a82f-5e71befd1078 | 2026-03-25 13:22:20.109470 |     144 |
+-----+-----------+--------------------------------------+----------------------------+---------+

→ id=100이 마지막 발급 (userId=82)
→ issued_at 13:22:20.186 → 로그의 remaining=0 시점과 일치
```

### event_handled 건수

```sql
mysql> SELECT event_type, COUNT(*) FROM event_handled WHERE event_type='COUPON_ISSUED' GROUP BY event_type;

+--------------+----------+
| event_type   | COUNT(*) |
+--------------+----------+
| COUPON_ISSUED|      200 |
+--------------+----------+

→ 테스트 1(100건) + 테스트 2(100건) = 200건
→ coupon_issued_log(200건)와 일치
→ 멱등성 기록도 정확 ✅
```

---

## Kafka 상태 — Consumer Group

```
GROUP              TOPIC                   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
coupon-issue-group coupon.issue.request-v1 0          200             200             0
coupon-issue-group coupon.issue.request-v1 1          -               0               -
coupon-issue-group coupon.issue.request-v1 2          1000            1000            0

Partition 0: 200건 (테스트 1 — couponId=1)  → LAG=0 (전부 처리 완료)
Partition 1: 0건 (사용 안 됨)
Partition 2: 1000건 (테스트 2 — couponId=2) → LAG=0 (전부 처리 완료)

→ couponId=1 → Partition 0, couponId=2 → Partition 2
→ 다른 쿠폰은 다른 파티션으로 라우팅됨 = 병렬 처리 가능
→ 같은 쿠폰은 같은 파티션 = 순차 처리 = 동시성 제어
```

---

## 전체 정합성 검증 요약

```
┌────────────────────┬─────────────────────┬─────────────────────┐
│ 검증 항목           │ 테스트 1 (couponId=1)│ 테스트 2 (couponId=2)│
├────────────────────┼─────────────────────┼─────────────────────┤
│ 요청 수             │ 200                 │ 1000                │
│ Kafka 발행 수       │ 200                 │ 1000                │
│ Consumer 수신 수    │ 200                 │ 1000                │
│ 발급 성공 (로그)    │ 100                 │ 100                 │
│ 재고 소진 (로그)    │ 100                 │ 900                 │
│ coupon_stock        │ issued=100/100      │ issued=100/100      │
│ coupon_issued_log   │ 100건               │ 100건               │
│ event_handled       │ 100건               │ 100건               │
│ DLQ                 │ 0건                 │ 0건                 │
│ Kafka LAG           │ 0                   │ 0                   │
│ 초과 발급           │ ✅ 0건              │ ✅ 0건              │
│ 정합성              │ ✅ 전부 일치        │ ✅ 전부 일치        │
├────────────────────┼─────────────────────┼─────────────────────┤
│ 발행 시간           │ 247ms               │ 65ms                │
│ 처리 시간 (100건)   │ ~2.37초             │ ~5.02초             │
│ 거부 처리 시간      │ ~1초                │ ~약 7초             │
│ 파티션              │ Partition 0         │ Partition 2         │
└────────────────────┴─────────────────────┴─────────────────────┘
```

---

## 관찰 포인트

```
1. 같은 쿠폰 = 같은 파티션 = 같은 Consumer 스레드
   - 테스트 1: ntainer#5-0-C-1 (Partition 0 담당)
   - 테스트 2: ntainer#5-2-C-1 (Partition 2 담당)
   - 다른 파티션이면 다른 Consumer → 쿠폰 간 병렬 처리 가능

2. userId 순서 ≠ 발급 순서
   - 200명이 50스레드로 동시 발행하므로 도착 순서는 비결정적
   - Kafka에 먼저 도착한 순서(offset 순)로 발급
   - 선착순의 "선착"은 Kafka offset 기준

3. 테스트 2가 테스트 1보다 건당 느림 (23.7ms → 50.2ms)
   - event_handled 테이블에 테스트 1의 100건이 이미 있음
   - SELECT 쿼리 비용이 증가 (인덱스 탐색 범위 넓어짐)
   - 운영에서는 event_handled 정기 삭제로 대응

4. 발행 속도 vs 처리 속도 차이
   - 발행: 1000건/65ms = 15,384건/초
   - 처리: 100건/5초 = 20건/초
   - → Kafka가 버퍼 역할. 발행이 빨라도 Consumer가 순차 처리하므로 안전

5. Partition 1은 사용 안 됨
   - couponId=1 → Partition 0, couponId=2 → Partition 2
   - Partition 1은 다른 couponId가 오면 사용됨
   - 3개 쿠폰을 동시에 실험하면 3개 파티션 모두 활용
```
