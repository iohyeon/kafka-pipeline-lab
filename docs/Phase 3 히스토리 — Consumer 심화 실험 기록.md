# Phase 3 히스토리 — Consumer 심화 실험 기록

---

## 설계 단계

### 의사결정 1: Consumer 3종류 분리

```
선택:
  1. manualAckFactory — 단건 수동 ACK (기본, 가장 안전)
  2. batchManualAckFactory — 배치 수동 ACK (처리량 최적화)
  3. autoCommitFactory — 자동 커밋 (비교 실험용, 실무 비권장)

각각 별도 ContainerFactory Bean으로 등록.

근거:
  - 같은 앱 안에서 용도별로 다른 소비 전략을 적용할 수 있어야 한다
  - 실무에서도 중요한 이벤트는 수동 ACK, 로그성 이벤트는 auto commit으로 분리하기도 함
  - containerFactory 이름으로 @KafkaListener에 지정
```

### 의사결정 2: SlowConsumer를 별도 토픽에 연결

```
선택: SlowConsumer → inventory.deduct.event-v1 토픽 사용

근거:
  - order.created.event-v1에 이미 OrderEventConsumer, BatchConsumer가 있음
  - SlowConsumer의 의도적 지연이 다른 Consumer에 영향 주면 안 됨
  - 별도 토픽 + 별도 Consumer Group으로 완전 격리
```

### 의사결정 3: Consumer concurrency = 파티션 수

```
설정: concurrency: 3 (파티션 수와 동일)

┌────────────┬─────────────────────────────────────────────┐
│ concurrency│ 효과                                         │
├────────────┼─────────────────────────────────────────────┤
│ < 파티션 수 │ 1 Consumer가 여러 파티션 담당 → 병렬도 낮음 │
│ = 파티션 수 │ 1:1 매핑 → 최적 병렬 처리 ✅                │
│ > 파티션 수 │ 놀고 있는 Consumer 발생 → 리소스 낭비        │
└────────────┴─────────────────────────────────────────────┘

선택: concurrency = 3 = 파티션 수
```

---

## 실험 결과 상세

### 실험 1: 정상 속도 메시지 소비 — LAG 확인

```
조건: delay=0ms, 50건 발행
결과:
  Partition 0: offset 20/20, LAG=0
  Partition 1: offset 10/10, LAG=0
  Partition 2: offset 20/20, LAG=0

→ 즉시 소비 완료. Consumer 3개가 각 파티션을 1:1로 담당.
```

### 실험 2: 느린 Consumer — LAG 적체 관찰

```
조건: delay=3000ms (3초/건), 30건 발행

5초 후:
  Partition 0: offset 22/32, LAG=10  ← 적체!
  Partition 1: offset 12/16, LAG=4
  Partition 2: offset 22/32, LAG=10

→ 3초/건 처리 속도 < 메시지 유입 속도 → LAG 쌓임
→ LAG = 아직 처리 안 된 메시지 수

지연 해제 후 15초:
  Partition 0: offset 32/32, LAG=0  ← 복구!
  Partition 1: offset 16/16, LAG=0
  Partition 2: offset 32/32, LAG=0

→ 처리 속도가 복구되면 LAG도 자연스럽게 해소
```

### 실험 3: Consumer Group 독립 소비 확인

```
order.created.event-v1 토픽에 2개 Consumer Group이 동시 연결:

  order-inventory-group (단건 Consumer):
    P0: offset 1203, LAG=0
    P1: offset 1095, LAG=0
    P2: offset 1037, LAG=0

  order-batch-group (배치 Consumer):
    P0: offset 1203, LAG=0
    P1: offset 1095, LAG=0
    P2: offset 1037, LAG=0

→ 같은 메시지를 두 Group이 독립 소비
→ 각 Group은 자체 offset 관리
→ 서로 영향 없음
→ 이것이 멘토링에서 배운 "Kafka의 1:N 분배" 구조의 실체
```

---

## 좋았던 점

```
1. SlowConsumer의 delay를 API로 동적 변경 가능
   → 앱 재시작 없이 3초 지연 → LAG 관찰 → 해제 → 복구를 실시간으로 확인
   → volatile 키워드로 멀티스레드 안전하게 처리

2. LAG을 숫자로 직접 확인
   → "적체가 쌓인다"는 추상적 개념이 아닌
   → "CURRENT-OFFSET=22, LOG-END-OFFSET=32, LAG=10"이라는 구체적 숫자

3. Consumer Group 독립 소비를 실측으로 증명
   → 같은 토픽의 같은 offset을 두 Group이 각각 소비하는 것을 확인
   → RabbitMQ의 "ACK하면 삭제"와의 구조적 차이를 체감

4. Batch Consumer가 실제로 List<ConsumerRecord>로 받는 것 확인
   → 단건 Consumer와 코드 차이를 직접 비교
```

---

## 발견한 문제점

```
1. containerFactory 변경 시 주의
   - 기존 OrderEventConsumer가 "kafkaListenerContainerFactory" (Spring 기본)를 사용
   - KafkaConsumerConfig에서 별도 Factory를 만들면서 기본 Factory 설정이 겹침
   - "manualAckFactory"로 명시적으로 변경하여 해결

2. Consumer Group ID 관리
   - 3종류의 Consumer가 같은 토픽을 구독할 때
   - Group ID가 같으면 파티션이 나눠지고 (같은 Group 내 분배)
   - Group ID가 다르면 독립 소비 (각 Group이 전부 읽음)
   - 실험 목적에 맞게 Group ID를 의도적으로 분리해야 함

3. Consumer 재시작 시 리밸런싱 발생
   - 앱 재시작 때마다 Consumer Group에서 이탈 → 재합류 → 리밸런싱
   - 로그에 "(Re-)joining group" 메시지가 반복됨
   - 프로덕션에서는 graceful shutdown으로 리밸런싱 최소화 필요
```

---

## 트레이드오프

```
┌──────────────────────┬────────────────────────────┬──────────────────────────────────┐
│ 항목                  │ 선택                        │ 포기한 것                         │
├──────────────────────┼────────────────────────────┼──────────────────────────────────┤
│ ACK 모드              │ Manual (가장 안전)          │ 코드 복잡도 (매번 ack 호출 필요)  │
│ 배치 vs 단건          │ 둘 다 구현                  │ 배치 실패 시 전체 재처리 vs 부분  │
│ concurrency           │ 3 (=파티션 수)              │ 리밸런싱 시 일시 중단            │
│ SlowConsumer 토픽 분리│ inventory 토픽 사용         │ order 토픽에서의 지연 실험 불가   │
│ auto-offset-reset     │ earliest                   │ 재시작 시 이전 메시지 재처리 가능 │
└──────────────────────┴────────────────────────────┴──────────────────────────────────┘
```

---

## 로그 관찰 — Consumer 시작/리밸런싱

```
앱 시작 시 Consumer Group 합류 과정:

1. Subscribed to topic(s): order.created.event-v1
2. Discovered group coordinator localhost:19092
3. (Re-)joining group
4. Successfully joined group with generation Generation{generationId=3}
5. Finished assignment:
   consumer-1 → [order.created.event-v1-0]
   consumer-2 → [order.created.event-v1-1]
   consumer-3 → [order.created.event-v1-2]
6. Setting offset for partition order.created.event-v1-0 to FetchPosition{offset=6}
7. partitions assigned: [order.created.event-v1-0]

→ Consumer Group Coordinator가 파티션을 할당
→ 이전 커밋된 offset부터 이어서 소비 시작
→ generation이 증가하면 이전 세션의 할당이 무효화
```
