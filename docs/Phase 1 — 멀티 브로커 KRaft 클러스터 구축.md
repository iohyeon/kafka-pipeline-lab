# Phase 1 — 멀티 브로커 KRaft 클러스터 구축

## 실습 레포
- 경로: `/Users/nahyeon/factory/kafka-pipeline-lab`
- Docker Compose: `docker/kafka-cluster-compose.yml`

---

## 왜 3 Broker인가?

### 단일 브로커의 문제

기존 프로젝트(`loop-pack-be-l2-vol3-java`)는 Kafka 브로커 1대로 운영했다.

```
Replication Factor: 1
Min ISR: 1
```

이 구성에서 브로커가 죽으면? **모든 토픽의 모든 파티션이 사라진다.** 리더도 없고, 팔로워도 없다. 데이터 유실 + 서비스 중단.

### 3 Broker + Replication Factor 3

```
Broker 1 (Leader)  ──→  Partition 0 [■■■■■]
Broker 2 (Follower) ──→  Partition 0 [■■■■■]  (복제본)
Broker 3 (Follower) ──→  Partition 0 [■■■■■]  (복제본)
```

- 3개 브로커에 동일한 데이터가 복제된다
- Leader가 죽으면 Follower 중 하나가 새 Leader로 선출
- **1대 죽어도 서비스 정상 운영, 데이터 유실 없음**

### 왜 3이 최소인가?

| Broker 수 | 죽어도 되는 수 | 투표 과반수 | 실무 적합성 |
|-----------|---------------|-----------|-----------|
| 1         | 0             | 1/1       | 개발용     |
| 2         | 0 (과반수 불가) | -         | 의미 없음  |
| 3         | 1             | 2/3       | 최소 운영  |
| 5         | 2             | 3/5       | 대규모     |

KRaft 모드에서 Controller가 **과반수 투표**로 Leader를 선출한다. 2대면 1대 죽었을 때 과반수(2/2)를 만들 수 없어서 선출 불가. **3대가 과반수 투표가 가능한 최소 홀수.**

---

## KRaft — ZooKeeper 없는 Kafka

### 기존 방식 (ZooKeeper)
```
Producer → Broker ← ZooKeeper (메타데이터 관리)
                 ↕
              Broker
                 ↕
              Broker
```
- ZooKeeper가 브로커 상태, 토픽 메타데이터, Controller 선출을 담당
- 문제: ZooKeeper 자체가 SPOF, 별도 클러스터 운영 필요, 확장 한계

### KRaft 방식 (Kafka 3.3+)
```
Broker 1 (Controller + Broker) ←→ Raft 합의
Broker 2 (Controller + Broker) ←→ Raft 합의
Broker 3 (Controller + Broker) ←→ Raft 합의
```
- **각 브로커가 Controller 역할을 겸한다** (process.roles=broker,controller)
- 메타데이터를 Raft 합의 프로토콜로 관리
- ZooKeeper 의존성 제거 → 운영 복잡도 감소, 부트스트랩 속도 향상

### Docker Compose에서의 KRaft 설정

```yaml
KAFKA_CFG_PROCESS_ROLES: "broker,controller"
KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: "1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
```

- `PROCESS_ROLES`: 이 노드가 수행할 역할. "broker,controller"면 둘 다.
- `CONTROLLER_QUORUM_VOTERS`: 투표에 참여하는 노드 목록. `노드ID@호스트:포트` 형식.

---

## ISR (In-Sync Replicas)

### ISR이란?

Leader와 **동기화가 완료된 Follower들의 집합**.

```
Leader (Broker 1): offset 100까지 기록
├── Follower (Broker 2): offset 100까지 복제 완료 → ISR ✅
└── Follower (Broker 3): offset 97까지만 복제    → ISR에서 제외 (lag 발생)
```

### Min ISR과 acks=all의 관계

```yaml
KAFKA_CFG_MIN_INSYNC_REPLICAS: "2"  # Docker Compose 설정
acks: all                            # Producer 설정
```

이 조합의 의미:

1. Producer가 메시지를 보낸다
2. Leader가 받는다
3. `acks=all` → Leader는 **ISR에 있는 모든 Follower가 복제를 확인**할 때까지 기다린다
4. `min.insync.replicas=2` → ISR에 최소 2개(Leader 포함)가 있어야 쓰기를 허용한다

**시나리오: Broker 3이 죽었을 때**
- ISR = {Broker 1(Leader), Broker 2} → 2개 → min.insync.replicas=2 충족 → **쓰기 성공**

**시나리오: Broker 2, 3 둘 다 죽었을 때**
- ISR = {Broker 1(Leader)} → 1개 → min.insync.replicas=2 미충족 → **쓰기 거부 (NotEnoughReplicasException)**

→ 이게 데이터 유실을 방지하는 메커니즘. 복제가 충분하지 않으면 아예 쓰기를 막는다.

---

## Listener 구조 — 왜 3개인가?

```yaml
KAFKA_CFG_LISTENERS: "INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,EXTERNAL://0.0.0.0:19092"
KAFKA_CFG_ADVERTISED_LISTENERS: "INTERNAL://kafka-1:9092,EXTERNAL://localhost:19092"
```

| Listener   | 포트  | 용도                          | 누가 사용하는가       |
|-----------|-------|------------------------------|-------------------|
| INTERNAL  | 9092  | 브로커 간 데이터 복제 + 내부 통신 | 다른 Kafka 브로커     |
| CONTROLLER| 9093  | KRaft 컨트롤러 합의 통신        | KRaft 컨트롤러      |
| EXTERNAL  | 19092 | Host(Spring Boot)에서 접근     | 개발자의 애플리케이션 |

### LISTENERS vs ADVERTISED_LISTENERS

- `LISTENERS`: 브로커가 **실제로 바인딩하는** 주소 (0.0.0.0 = 모든 인터페이스)
- `ADVERTISED_LISTENERS`: 클라이언트에게 **"나한테 연결하려면 이 주소로 와"**라고 알려주는 주소

왜 다른가? Docker 컨테이너 안에서는 `kafka-1:9092`가 맞지만, Host(Mac)에서는 `localhost:19092`로 접근해야 한다. 같은 브로커인데 누가 물어보느냐에 따라 다른 주소를 알려줘야 한다.

---

## Topic 설계

### 토픽 네이밍 컨벤션

```
{도메인}.{액션}.{타입}-{버전}
```

| 토픽명                      | key        | 파티션 수 | 용도                     |
|----------------------------|-----------|---------|------------------------|
| order.created.event-v1     | orderId   | 3       | 주문 생성 이벤트           |
| coupon.issue.request-v1    | couponId  | 3       | 쿠폰 발급 요청 (동시성 제어) |
| inventory.deduct.event-v1  | productId | 3       | 재고 차감 이벤트           |
| pipeline.dlq-v1            | -         | 1       | 실패 메시지 격리           |

### Key 설계가 순서 보장의 핵심

```
key: orderId=54333 → hashCode() % 3 = 0 → Partition 0
key: orderId=54334 → hashCode() % 3 = 1 → Partition 1
key: orderId=54333 → hashCode() % 3 = 0 → Partition 0  ← 같은 파티션!
```

같은 orderId는 항상 같은 파티션 → 파티션 내 순서 보장 → 같은 주문에 대한 이벤트는 순차 처리.

**쿠폰은 왜 couponId가 key인가?** (케브님 멘토링 핵심)
- userId가 key면? → 같은 유저의 요청은 순차 처리되지만, 같은 쿠폰에 대한 다른 유저의 요청은 다른 파티션으로 분산 → 쿠폰 수량 초과 발급 위험
- couponId가 key면? → 같은 쿠폰에 대한 모든 요청이 같은 파티션 → 순차 처리로 동시성 제어

---

## 프로젝트 구조

```
kafka-pipeline-lab/
├── docker/
│   └── kafka-cluster-compose.yml    # 3-Broker KRaft + Kafka UI + MySQL
├── src/main/java/com/pipeline/
│   ├── KafkaPipelineLabApplication.java
│   ├── api/
│   │   └── KafkaTestController.java  # Phase 1 검증용 API
│   ├── config/
│   │   └── KafkaTopicConfig.java     # Topic 설계 (Bean)
│   ├── consumer/
│   │   └── OrderEventConsumer.java   # Manual ACK Consumer
│   ├── event/
│   │   └── OrderCreatedEvent.java    # 이벤트 DTO
│   └── producer/
│       └── OrderEventProducer.java   # Key 기반 Producer
└── src/main/resources/
    └── application.yml               # Kafka + JPA 설정
```

---

## 실행 방법

### 1. 클러스터 기동
```bash
cd /Users/nahyeon/factory/kafka-pipeline-lab
docker compose -f docker/kafka-cluster-compose.yml up -d
```

### 2. 클러스터 상태 확인
```bash
# 브로커 3대 모두 healthy인지 확인
docker compose -f docker/kafka-cluster-compose.yml ps

# Kafka UI 접속
open http://localhost:9099
```

### 3. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 4. 메시지 발행 테스트
```bash
# 단건
curl -X POST http://localhost:8085/api/test/order/54333

# 10건 벌크 — Kafka UI에서 파티션 분배 확인
curl -X POST http://localhost:8085/api/test/orders/bulk/10
```

### 5. 확인 포인트 (Kafka UI에서)
- [ ] 토픽 4개 자동 생성됐는가?
- [ ] 각 토픽에 파티션 3개, 복제본 3개인가?
- [ ] 같은 orderId로 여러 번 발행 → 같은 파티션에 들어가는가?
- [ ] Consumer Group 탭에서 order-inventory-group이 보이는가?
- [ ] offset이 증가하는가?

---

## 다음 단계: Phase 2 — Producer 심화

- acks 설정별 동작 차이 실험 (acks=0, 1, all)
- idempotence=true가 실제로 중복을 막는지 테스트
- Partition Key 라우팅 검증 (같은 key → 같은 파티션)
- Producer 콜백 에러 핸들링
