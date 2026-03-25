# Phase 2 히스토리 — Producer 심화 실험 기록

---

## 설계 단계

### 의사결정 1: acks별 KafkaTemplate을 Bean으로 분리

```
선택지:
  A. 하나의 KafkaTemplate + 런타임에 설정 변경
  B. acks별 3개 KafkaTemplate Bean 생성

선택: B

근거:
  - KafkaTemplate은 ProducerFactory 생성 시점에 설정이 고정됨
  - 런타임 변경은 Producer 재생성이 필요 → 비효율적
  - Bean 이름으로 주입 (acksZeroKafkaTemplate, acksOneKafkaTemplate, acksAllKafkaTemplate)
  - 실험 목적에 맞게 명확한 분리

주의점:
  - Spring Boot 자동 설정이 만드는 기본 KafkaTemplate과 충돌할 수 있음
  - 기존 OrderEventProducer의 주입을 acksAllKafkaTemplate으로 명시적 변경 필요
```

### 의사결정 2: 기본 Producer를 acks=all로

```
선택: acks=all + idempotence=true

근거:
  - 주문/결제/쿠폰 도메인에서 메시지 유실은 치명적
  - 성능 차이보다 안전성이 우선
  - idempotence로 Producer 레벨 중복 방지까지 확보
```

---

## 실험 결과 상세

### 실험 1: acks 비교 — 단건

```
orderId=54333으로 동일 메시지를 3가지 acks로 발행:

  acks=0  : 271ms
  acks=1  : 102ms
  acks=all: 59ms

관찰:
  - acks=0이 가장 느린 이유 → 첫 발행이라 Producer 초기화 비용(connection, metadata fetch) 포함
  - acks=all이 가장 빠른 이유 → 마지막에 실행돼서 connection이 이미 warm up 됨
  - 단건 비교로는 acks 동작 차이를 정확히 비교하기 어려움
  - 벌크로 비교해야 의미 있는 결과
```

### 실험 2: acks 비교 — 벌크 100건 / 1000건

```
100건:
  acks=0  : 103ms
  acks=1  : 217ms
  acks=all: 160ms

1000건:
  acks=0  : 969ms
  acks=1  : 347ms
  acks=all: 117ms  ← ?!

핵심 발견:
  KafkaTemplate.send()는 비동기(non-blocking)이다.
  send() 호출 = 내부 버퍼(RecordAccumulator)에 메시지를 넣는 것.
  실제 브로커 전송은 별도 Sender 스레드가 배치로 처리.

  측정한 시간 = "버퍼에 넣는 시간"이지 "브로커 왕복 시간"이 아님.

  acks=all + idempotence=true 조합에서
  Kafka Producer가 배치 최적화를 더 적극적으로 하기 때문에
  벌크 전송 시 오히려 빨라질 수 있음.

  → "acks=all이 느리다"는 동기 전송 기준의 이야기.
  → 비동기에서는 배치, 버퍼, 네트워크 상태에 따라 달라진다.
  → 결론: 성능이 아닌 안전성으로 acks를 선택해야 한다.
```

### 실험 3: Key 라우팅 검증

```
orderId 1~6, 각 3번 발행 → 18건

결과:
  orderId=1 → Partition 0 (attempt 1,2,3 모두)  offset: 144,145,146
  orderId=2 → Partition 2 (attempt 1,2,3 모두)  offset: 71,72,73
  orderId=3 → Partition 2 (attempt 1,2,3 모두)  offset: 74,75,76
  orderId=4 → Partition 1 (attempt 1,2,3 모두)  offset: 102,103,104
  orderId=5 → Partition 0 (attempt 1,2,3 모두)  offset: 147,148,149
  orderId=6 → Partition 1 (attempt 1,2,3 모두)  offset: 105,106,107

검증 결과:
  ✅ 같은 key → 100% 같은 파티션
  ✅ 파티션 내 offset 순차 증가
  ✅ 6개 key가 3개 파티션에 균등 분배 (P0:2개, P1:2개, P2:2개)
```

---

## 좋았던 점

```
1. acks 비교를 API로 만들어서 반복 실험이 쉬움
   → curl 한 줄로 결과 즉시 확인
   → 설정 변경 없이 3가지 모드 동시 비교

2. Key 라우팅이 실제로 동작하는 걸 숫자로 증명
   → "이론적으로 같은 파티션에 간다"가 아닌 "offset 144,145,146으로 실측 확인"

3. 비동기 send()의 특성을 실험으로 발견
   → acks=all이 벌크에서 빠른 건 예상 밖의 결과
   → "측정하는 것이 무엇인지"를 정확히 이해하게 됨
```

---

## 트레이드오프

```
┌──────────────────────┬────────────────────────┬──────────────────────────────┐
│ 항목                  │ 선택                    │ 포기한 것                     │
├──────────────────────┼────────────────────────┼──────────────────────────────┤
│ 기본 acks             │ all (가장 안전)         │ 극단적 저지연 (acks=0)        │
│ idempotence           │ true                   │ max.in.flight > 5 불가       │
│ KafkaTemplate 분리    │ Bean 3개               │ 메모리 사용량 증가 (3개 Producer)│
│ 비동기 send()         │ non-blocking           │ 발행 실패 즉시 감지 불가      │
│                       │                        │ (callback으로만 에러 확인)    │
└──────────────────────┴────────────────────────┴──────────────────────────────┘
```
