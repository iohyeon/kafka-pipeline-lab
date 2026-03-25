# Outbox Relay의 비동기 vs 동기 — @Transactional 경계 문제 심화

---

## 발생한 문제

Outbox Relay가 PENDING 이벤트를 Kafka로 발행하고 PUBLISHED로 바꿔야 하는데, **매 Polling마다 같은 이벤트를 무한 반복 발행**했다.

```
로그:
  [Relay] PENDING 이벤트 1건 발행 시작
  [Relay] 발행 성공 — outboxId=1, partition=1, offset=1095
  [Relay] PENDING 이벤트 1건 발행 시작
  [Relay] 발행 성공 — outboxId=1, partition=1, offset=1096   ← 같은 outboxId!
  [Relay] PENDING 이벤트 1건 발행 시작
  [Relay] 발행 성공 — outboxId=1, partition=1, offset=1097   ← 또!
```

Kafka에는 발행 성공했는데, DB에서는 계속 PENDING으로 남아있었다.

---

## 원인 분석 — @Transactional과 비동기 콜백의 충돌

### @Transactional의 동작 원리

```
@Transactional이 붙은 메서드의 생명주기:

  ① 프록시가 메서드 호출을 가로챔
  ② TX 시작 (EntityManager.getTransaction().begin())
  ③ 실제 메서드 실행
  ④ 메서드 리턴
  ⑤ Dirty Checking — 영속성 컨텍스트에서 변경된 Entity 감지
  ⑥ 변경된 Entity에 대한 UPDATE SQL 생성
  ⑦ TX 커밋 (flush + commit)
  ⑧ 영속성 컨텍스트 종료

핵심: ⑤⑥⑦은 "메서드가 리턴된 후"에 프록시가 처리한다.
      메서드 안에서 Entity를 변경하면 ⑤에서 감지되어 ⑥에서 UPDATE가 나간다.
      하지만 메서드가 이미 리턴된 "이후"에 Entity를 변경하면? → 감지 불가.
```

### 비동기 whenComplete의 실행 시점

```
시간축:

  Thread-1 (scheduling-1)                Thread-2 (kafka-producer-network)
  ─────────────────────────              ─────────────────────────────────

  ① TX 시작
  ② findPendingEvents()
  ③ kafkaTemplate.send()
     → RecordAccumulator에 넣음
     → CompletableFuture 반환
     → whenComplete 콜백 등록
  ④ for 루프 끝
  ⑤ 메서드 리턴
  ⑥ Dirty Checking 실행
     → event 변경 없음 (markPublished 안 됨)
  ⑦ TX 커밋 (상태 변경 없이)
  ⑧ 영속성 컨텍스트 종료
                                          ⑨ Kafka 브로커에서 ACK 수신
                                          ⑩ whenComplete 콜백 실행
                                             → event.markPublished()
                                             → Entity 상태는 변경됨
                                             → 하지만 영속성 컨텍스트는 이미 닫힘!
                                             → DB에 UPDATE 안 나감!

  결과: DB에 PENDING 유지 → 다음 Polling에서 또 조회 → 무한 반복
```

### 핵심 개념: 영속성 컨텍스트의 생명주기

```
┌─────────────────────────────────────────────────────────────────────┐
│  JPA Dirty Checking은 영속성 컨텍스트가 살아있을 때만 동작한다.      │
│                                                                     │
│  @Transactional 메서드가 끝나면:                                    │
│  1. 영속성 컨텍스트가 flush (변경 감지 → SQL 생성)                  │
│  2. TX 커밋                                                         │
│  3. 영속성 컨텍스트 close                                           │
│                                                                     │
│  이후에 Entity의 필드를 바꿔도:                                     │
│  - 그 Entity는 "준영속(detached)" 상태                              │
│  - 더 이상 JPA가 관리하지 않음                                      │
│  - DB에 반영되지 않음                                               │
│                                                                     │
│  비동기 콜백이 실행되는 시점 = 영속성 컨텍스트가 이미 close된 후     │
│  → Dirty Checking 불가 → DB 반영 불가                              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 해결 방법 비교 — 3가지 접근

### 방법 1: 동기 .get() (현재 적용)

```java
@Transactional
public void relayPendingEvents() {
    List<OutboxEvent> events = findPendingEvents();
    for (OutboxEvent event : events) {
        SendResult result = kafkaTemplate.send(...).get(10, SECONDS);  // 블로킹
        event.markPublished();  // TX 안에서 실행 → Dirty Checking 가능
    }
}  // TX 커밋 → UPDATE 반영
```

```
장점:
  ✅ 간단하고 확실함
  ✅ TX 안에서 상태 변경 → DB 반영 보장
  ✅ 발행 실패 시 즉시 markFailed() → 역시 DB 반영

단점:
  ❌ Kafka 응답을 동기 대기 → Relay가 느려짐
  ❌ 50건 배치 × 건당 최대 10초 = 최악 500초 블로킹
  ❌ 하나의 발행이 느리면 뒤의 이벤트도 대기
```

### 방법 2: 비동기 + 별도 TX로 상태 업데이트

```java
// Relay — TX 없이 실행
public void relayPendingEvents() {
    List<OutboxEvent> events = outboxEventRepository.findPendingEvents(50);

    for (OutboxEvent event : events) {
        kafkaTemplate.send(...).whenComplete((result, ex) -> {
            if (ex == null) {
                outboxStatusUpdater.markPublished(event.getId());  // 별도 TX!
            } else {
                outboxStatusUpdater.markFailed(event.getId(), ex.getMessage());
            }
        });
    }
}

// 별도 서비스 — 자체 TX
@Service
public class OutboxStatusUpdater {
    @Transactional
    public void markPublished(Long outboxId) {
        OutboxEvent event = outboxEventRepository.findById(outboxId).orElseThrow();
        event.markPublished();
    }  // 여기서 TX 커밋 → DB 반영
}
```

```
장점:
  ✅ 비동기 유지 → Relay 빠름
  ✅ 콜백에서 새 TX를 열어서 상태 변경 → DB 반영 가능

단점:
  ❌ 코드 복잡도 증가 (별도 서비스 필요)
  ❌ 콜백의 TX와 조회의 TX가 다름 → 동시성 이슈 가능
  ❌ 콜백 실행 전에 다음 Polling이 돌면? → 같은 이벤트 중복 발행
     (PENDING 조회 → 발행 → 콜백 아직 안 옴 → 다음 Polling이 또 PENDING 조회)
```

### 방법 3: 비동기 + 낙관적 락(version)으로 중복 방지

```java
// OutboxEvent에 @Version 추가
@Version
private Long version;

// Relay
public void relayPendingEvents() {
    List<OutboxEvent> events = outboxEventRepository.findPendingEvents(50);

    // 먼저 상태를 PROCESSING으로 변경 (즉시 커밋)
    for (OutboxEvent event : events) {
        outboxStatusUpdater.markProcessing(event.getId());  // PENDING → PROCESSING
    }

    // 비동기 발행
    for (OutboxEvent event : events) {
        kafkaTemplate.send(...).whenComplete((result, ex) -> {
            if (ex == null) {
                outboxStatusUpdater.markPublished(event.getId());
            }
        });
    }
}
```

```
장점:
  ✅ 비동기 유지
  ✅ PROCESSING 상태로 다음 Polling에서 중복 조회 방지
  ✅ 낙관적 락으로 동시성 안전

단점:
  ❌ 상태가 3개 → 4개로 증가 (PENDING→PROCESSING→PUBLISHED/FAILED)
  ❌ PROCESSING 상태에서 앱이 죽으면? → 영원히 PROCESSING에 갇힘
     → 별도 타임아웃 로직 필요 (예: 5분 이상 PROCESSING이면 PENDING으로 복귀)
  ❌ 코드 복잡도 상당히 증가
```

---

## 어떤 방법이 맞는가?

```
┌──────────┬──────────┬──────────────┬──────────────┬────────────────────┐
│          │ 정확성   │ 성능          │ 코드 복잡도   │ 실무 적용도         │
├──────────┼──────────┼──────────────┼──────────────┼────────────────────┤
│ 방법 1   │ ✅ 확실  │ ❌ 느림       │ ✅ 단순      │ 중소 규모 적합      │
│ (동기)   │          │ (블로킹 대기) │              │                    │
├──────────┼──────────┼──────────────┼──────────────┼────────────────────┤
│ 방법 2   │ △ 중복   │ ✅ 빠름      │ △ 중간       │ 대규모 가능         │
│ (비동기  │ 발행     │ (논블로킹)    │ (별도 서비스) │ 하지만 중복 이슈    │
│ +별도TX) │ 가능     │              │              │                    │
├──────────┼──────────┼──────────────┼──────────────┼────────────────────┤
│ 방법 3   │ ✅ 안전  │ ✅ 빠름      │ ❌ 복잡      │ 대규모 + 높은 안전  │
│ (비동기  │          │              │ (상태 4개,   │ 요구 시             │
│ +PROCESS)│          │              │  타임아웃)    │                    │
└──────────┴──────────┴──────────────┴──────────────┴────────────────────┘
```

### 현재 선택: 방법 1 (동기)

```
근거:
  1. Outbox Relay는 "실시간 처리"가 아니다.
     - 이미 5초 Polling 간격이 있다.
     - 몇 초 느려져도 비즈니스에 영향 없다.

  2. 학습 프로젝트에서 복잡도를 올리는 것보다 동작 원리를 명확히 이해하는 게 우선.

  3. 실무에서도 Outbox Relay 성능이 병목이 되는 경우는 드물다.
     - 병목이 되면 그때 방법 3으로 전환하면 된다.
     - 대부분의 경우 Polling 간격을 줄이거나 배치 크기를 늘리는 것으로 충분.

  4. 동기 방식의 "최악 시나리오"도 사실 제한적:
     - 건당 Kafka 응답 = 보통 10~50ms
     - 50건 배치 = 500ms~2.5s
     - Polling 간격 5초 안에 충분히 처리 가능
```

### 만약 비동기로 가야 한다면?

```
비동기가 필요해지는 시점:
  - 초당 수만 건의 이벤트가 Outbox에 쌓이는 경우
  - Relay의 처리 속도가 이벤트 생성 속도를 따라잡지 못하는 경우
  - Kafka 응답이 수백ms 이상으로 느려지는 경우

그때는 방법 3 (PROCESSING 상태)을 도입하되:
  1. OutboxStatus에 PROCESSING 추가
  2. Relay가 조회 시 PENDING → PROCESSING으로 즉시 변경 (조회와 같은 TX)
  3. 비동기 발행 후 콜백에서 PROCESSING → PUBLISHED (별도 TX)
  4. 5분 이상 PROCESSING이면 PENDING으로 복귀하는 타임아웃 스케줄러 추가
```

---

## 이 문제에서 배운 것

```
1. @Transactional의 TX 경계 = 메서드 리턴 시점
   → 비동기 콜백은 TX 밖에서 실행된다
   → Entity 변경이 DB에 반영되지 않는다

2. JPA Dirty Checking은 영속성 컨텍스트가 살아있을 때만 동작
   → 영속성 컨텍스트 close 후에 Entity를 변경하면 "준영속(detached)" 상태
   → DB에 반영 안 됨

3. 비동기 + @Transactional 조합은 항상 주의해야 한다
   → 콜백에서 DB를 변경하려면 새로운 TX를 열어야 한다
   → 또는 동기로 전환하거나, 상태 머신을 복잡하게 설계해야 한다

4. "더 빠른 방법"이 항상 좋은 건 아니다
   → Outbox Relay는 정확성이 목적. 속도는 부차적.
   → 최적화는 병목이 확인된 후에 해도 늦지 않다.
```

---

## 연관 학습 키워드

```
이 문제를 완전히 이해하려면 알아야 하는 것들:

1. Spring @Transactional의 프록시 동작 원리
   → 이미 학습: Spring 공부/48 (AOP Proxy와 @Transactional)
   → 이미 학습: Spring 공부/49 (Self-invocation)

2. JPA 영속성 컨텍스트 생명주기
   → 이미 학습: Spring 공부/47 (영속성 컨텍스트와 readOnly)
   → 추가: 준영속(detached) 상태에서의 동작

3. CompletableFuture와 스레드 경계
   → whenComplete는 어떤 스레드에서 실행되는가?
   → KafkaTemplate의 경우 kafka-producer-network 스레드에서 실행

4. Outbox 패턴의 CDC(Change Data Capture) 대안
   → Polling 대신 Debezium이 binlog를 감시 → 더 빠르고 DB 부하 적음
   → 하지만 인프라 복잡도 증가
```
