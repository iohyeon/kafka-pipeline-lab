# kafka-pipeline-lab

이벤트 기반 아키텍처(EDA)의 핵심 패턴을 **실무 수준으로 설계하고, 구현하고, 실험하고, 검증**한 프로젝트입니다.

단순히 Kafka를 연동하는 수준이 아닌, 실무에서 마주치는 **정합성 문제**(DB-Kafka 원자성), **동시성 제어**(Lock-free 순차 처리), **장애 복구**(DLQ + 재시도), **중복 처리 방지**(Consumer 멱등성)를 직접 설계하고 실측 데이터로 검증했습니다.

## Tech Stack

- **Runtime**: Java 21, Spring Boot 3.4.4
- **Messaging**: Apache Kafka 3.5.1 (KRaft, 3-Broker Cluster)
- **Persistence**: MySQL 8.0, Spring Data JPA
- **Monitoring**: Prometheus, Grafana, Kafka UI
- **CDC**: Debezium (Kafka Connect) — Outbox 테이블 binlog → Kafka 자동 전달
- **Infra**: Docker Compose (Multi-Broker + Monitoring Stack)

## Architecture Overview

```
API Request (N concurrent users)
     │
     ▼
┌─────────────────────────────────────────────────────────┐
│  Transactional Outbox                                    │
│  @Transactional { business_save + outbox_save }          │
│  → Polling Relay (sync .get()) → at-least-once delivery │
└────────────────────────┬────────────────────────────────┘
                         │
                    acks=all + idempotent producer
                    key-based partition routing
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│  Kafka Cluster (3 Broker, KRaft)                         │
│  Replication Factor=3, Min ISR=2                         │
│  1 broker failure tolerance, zero data loss              │
└────────────────────────┬────────────────────────────────┘
                         │
                    Manual ACK, single-record processing
                    1 Partition : 1 Consumer (sequential)
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│  Consumer Pipeline                                       │
│  Idempotency Check (event_handled + same TX)             │
│  → Business Logic                                        │
│  → Retry (3x, exponential backoff)                       │
│  → DLQ Isolation (preserve original metadata in headers) │
└─────────────────────────────────────────────────────────┘
```

## 7-Phase Implementation

각 Phase는 **실무에서 마주치는 문제를 정의**하고, **설계 의사결정의 근거를 밝히고**, **실측 데이터로 검증**하는 구조로 진행했습니다.

### Phase 1 — Multi-Broker KRaft Cluster

3-Broker KRaft 클러스터를 구성하여 **단일 장애점(SPOF)을 제거**하고, Replication Factor 3 + Min ISR 2 조합으로 **1대 장애 시에도 쓰기 가능, 2대 장애 시 쓰기 거부**로 데이터 유실을 방지하는 구조를 설계했습니다.

**설계 포인트**:
- KRaft 합의 프로토콜로 ZooKeeper 의존성 제거 (Kafka 3.3+)
- 3개 Listener 분리 (INTERNAL/CONTROLLER/EXTERNAL) — 브로커 간 통신, 컨트롤러 합의, 호스트 접근을 물리적으로 격리
- Topic 네이밍 컨벤션: `{도메인}.{액션}.{타입}-{버전}` (e.g., `order.created.event-v1`)

### Phase 2 — Producer Guarantees

`acks=0/1/all` 설정별 동작 차이를 **벌크 1000건 실측**으로 비교하고, `enable.idempotence=true`의 PID+Sequence Number 기반 중복 전송 방지 메커니즘을 검증했습니다.

**설계 포인트**:
- Partition Key 라우팅 검증 — 같은 key는 100% 같은 파티션에 적재됨을 실측 확인 (murmur2 해시)
- 비동기 `send()`의 특성 — 측정값은 RecordAccumulator 버퍼 적재 시간이며, 실제 브로커 왕복 시간은 콜백에서만 관측 가능
- acks 선택 기준은 성능이 아닌 **안전성** — 주문/결제 도메인에서는 `acks=all`이 필수

### Phase 3 — Consumer Reliability

Manual ACK과 Auto Commit의 차이를 실험하고, **LAG 적체/복구 시나리오**를 SlowConsumer로 재현했습니다. Consumer Group 독립 소비를 실측하여 **Kafka의 1:N 분배 구조**를 검증했습니다.

**설계 포인트**:
- `enable.auto.commit=false` + `ack-mode=MANUAL` — 처리 완료 후에만 offset 커밋하여 at-least-once 보장
- `concurrency = partition 수` — 1:1 매핑으로 최적 병렬 처리
- Batch Listener vs Single Record — 처리량(throughput) 요구에 따른 선택

### Phase 4 — Transactional Outbox Pattern

**DB 트랜잭션과 Kafka 발행의 원자성 문제**를 Outbox 패턴으로 해결했습니다. 비즈니스 데이터와 Outbox 이벤트를 같은 `@Transactional`로 묶어 커밋의 원자성을 보장하고, Polling Relay가 주기적으로 PENDING 이벤트를 Kafka로 발행합니다.

**설계 포인트**:
- **비동기 콜백 + @Transactional 충돌 버그 발견 및 해결** — `whenComplete` 콜백은 TX 커밋 이후에 실행되어 Entity 상태 변경이 DB에 반영되지 않는 문제. 동기 `.get()`으로 전환하여 TX 경계 안에서 상태 변경을 보장
- Relay 동기/비동기 선택 — 실무 표준은 **동기 Polling → CDC(Debezium) 전환 경로**. 비동기 Polling은 복잡도 대비 이점이 CDC보다 못해 실무에서 거의 사용하지 않음
- 재시도 5회 + FAILED 상태 격리 + 7일 이전 PUBLISHED 정리 스케줄러

### Phase 5 — Consumer Idempotency

at-least-once 보장에서 발생하는 **중복 메시지 문제**를 `event_handled` 테이블의 UNIQUE 제약 조건으로 해결했습니다. 비즈니스 로직과 멱등성 기록을 **같은 트랜잭션**으로 묶어, 비즈니스 실패 시 멱등 기록도 롤백되어 재처리가 가능합니다.

**설계 포인트**:
- **DB vs Redis 멱등성 저장소** — Redis SET NX는 비즈니스 실패 후 재처리 불가(SET NX 성공 → 비즈니스 실패 → Redis에 "처리됨" 잔존). DB는 같은 TX 롤백으로 자연스럽게 해결
- UNIQUE 제약 조건이 **DB 레벨에서 동시성 방어** — 2개 Consumer가 동시에 같은 eventId를 처리해도 1개만 INSERT 성공, 나머지는 DataIntegrityViolationException → TX 롤백
- 실측: 10건 동일 eventId 발행 → 1건만 처리, 9건 스킵 (건당 4ms)

### Phase 6 — Dead Letter Queue + Error Handling

처리 실패 메시지를 **3회 지수 백오프 재시도**(1초→2초→4초) 후 **DLQ 토픽으로 격리**합니다. DLQ 메시지에는 원본 토픽/파티션/오프셋/에러 메시지를 헤더로 보존하여 **장애 추적과 후처리**가 가능합니다.

**설계 포인트**:
- 지수 백오프 — 일시적 장애(DB 커넥션 풀 고갈, 네트워크 순단)의 복구 대기. 고정 간격 재시도보다 장애 상황에서 부하를 줄임
- DLQ 헤더 설계 — `X-Original-Topic`, `X-Original-Partition`, `X-Original-Offset`, `X-Error-Message`, `X-Error-Timestamp`, `X-Retry-Count`
- DLQ retention 30일 (일반 토픽 7일보다 길게) — 실패 메시지는 원인 분석을 위해 오래 보관

### Phase 7 — First-Come-First-Served Coupon Issuance

Phase 1~6의 모든 기법을 통합하여 **선착순 쿠폰 발급 시나리오**를 구현하고, **200명/1000명 동시 요청**에서 **Lock 없이 정확히 100장만 발급**되는 것을 검증했습니다.

**동시성 제어 구조**:
```
key = couponId → murmur2(couponId) % 3 → 같은 파티션
→ 1 Consumer가 순차 처리 → DB 재고 체크가 직렬화
→ Lock 없이도 초과 발급 방지
```

**실측 결과**:

| | 200명 테스트 | 1000명 테스트 |
|---|---|---|
| 요청 수 | 200 | 1000 |
| 발급 수 | 100 | 100 |
| 거부 수 | 100 | 900 |
| 초과 발급 | 0건 | 0건 |
| 정합성 (stock = log) | 일치 | 일치 |
| 발행 시간 | 247ms | 65ms |

### 심화 — Transaction Phase Comparison

Phase 4(Outbox)에서 발견한 `@Transactional + 비동기 콜백 충돌` 문제를 깊이 파고들어, **3가지 이벤트 발행 방식**을 구현하고 동작 차이를 실측 비교했습니다.

| 방식 | 동작 | TX 롤백 시 |
|------|------|-----------|
| **A. BEFORE_COMMIT** | 도메인 이벤트 → 리스너가 같은 TX에서 Outbox 저장 | Outbox도 롤백 (안전) |
| **B. AFTER_COMMIT** | TX 커밋 후 Kafka 즉시 발행 시도 | Kafka 발행 안 됨 (안전) |
| **C. DIRECT_CALL** | Facade가 Outbox 직접 저장 → Relay가 나중에 발행 | Outbox도 롤백 (안전) |

```bash
# 3가지 방식 비교 실험
curl -X POST http://localhost:8085/api/comparison/before-commit/1
curl -X POST http://localhost:8085/api/comparison/after-commit/2
curl -X POST http://localhost:8085/api/comparison/direct-call/3

# 실패 시나리오 — TX 롤백 시 Outbox 상태 확인
curl -X POST http://localhost:8085/api/comparison/before-commit-fail/4
curl -X POST http://localhost:8085/api/comparison/direct-call-fail/5
```

### 심화 — CDC (Debezium) 인프라 검증

Outbox 패턴의 궁극적 전환 경로인 **CDC(Change Data Capture)**를 Docker Compose에 구축하여 실증했습니다. MySQL binlog → Debezium → Kafka 자동 전달로, **Relay 코드 없이** Outbox INSERT가 즉시 Kafka 토픽에 도달하는 것을 확인.

```bash
# CDC 포함 클러스터 실행
docker compose -f docker/kafka-cluster-compose.yml up -d

# Debezium Connector 등록 후 Outbox INSERT → Kafka 자동 전달 확인
# Kafka UI (localhost:9099)에서 토픽 메시지 확인
```

## Project Structure

```
kafka-pipeline-lab/
├── docker/
│   ├── kafka-cluster-compose.yml       # 3-Broker KRaft + Kafka UI + MySQL
│   └── monitoring-compose.yml          # Prometheus + Grafana
├── docs/                               # 22개 실험/설계/회고 문서
├── src/main/java/com/pipeline/
│   ├── api/                            # Phase별 실험 API (8개 Controller)
│   ├── comparison/                     # BEFORE_COMMIT vs AFTER_COMMIT vs DIRECT_CALL 비교
│   ├── config/                         # Kafka Topic/Producer/Consumer 설정
│   ├── consumer/                       # Manual ACK, Batch, Slow, Retryable Consumer
│   ├── coupon/                         # 선착순 쿠폰 도메인 (Entity, Service, Consumer)
│   ├── dlq/                            # DLQ Publisher + Monitor Consumer
│   ├── idempotency/                    # event_handled Entity + Service
│   ├── outbox/                         # Outbox Entity + Polling Relay
│   └── producer/                       # acks 비교, Key 라우팅 검증 Producer
└── src/main/resources/
    └── application.yml
```

## Quick Start

```bash
# 1. Clone
git clone https://github.com/iohyeon/kafka-pipeline-lab.git
cd kafka-pipeline-lab

# 2. Kafka Cluster + MySQL
docker compose -f docker/kafka-cluster-compose.yml up -d

# 3. Monitoring (optional)
docker compose -f docker/monitoring-compose.yml up -d

# 4. Application
./gradlew bootRun

# 5. Test
# 쿠폰 100장 생성
curl -X POST "http://localhost:8085/api/experiment/coupon/create?couponId=1&name=welcome-coupon&quantity=100"

# 200명 동시 발급 요청
curl -X POST "http://localhost:8085/api/experiment/coupon/concurrent-test?couponId=1&userCount=200&threads=50"

# 30초 후 결과 확인
curl http://localhost:8085/api/experiment/coupon/result?couponId=1
```

**Endpoints**:

| Service | URL |
|---------|-----|
| Application | http://localhost:8085 |
| Kafka UI | http://localhost:9099 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |

## Documentation

`docs/` 디렉토리에 22개의 설계/실험/회고 문서가 있습니다. 각 Phase별로 **설계 의사결정**, **실측 로그**, **트레이드오프 분석**, **버그 발견 및 해결 과정**을 기록했습니다.

### Phase 설계 문서 (7개)

| Phase | 문서 |
|-------|------|
| 1 | [멀티 브로커 KRaft 클러스터 구축](docs/Phase%201%20—%20멀티%20브로커%20KRaft%20클러스터%20구축.md) |
| 2 | [Producer 심화 (acks, idempotence, key 라우팅)](docs/Phase%202%20—%20Producer%20심화%20(acks,%20idempotence,%20key%20라우팅).md) |
| 3 | [Consumer 심화 (리밸런싱, Manual ACK, Batch, LAG)](docs/Phase%203%20—%20Consumer%20심화%20(리밸런싱,%20Manual%20ACK,%20Batch,%20LAG).md) |
| 4 | [Transactional Outbox 패턴 구현](docs/Phase%204%20—%20Transactional%20Outbox%20패턴%20구현.md) |
| 5 | [멱등성 처리 (event_handled + 같은 TX)](docs/Phase%205%20—%20멱등성%20처리%20(event_handled%20+%20같은%20TX).md) |
| 6 | [DLQ + 에러 핸들링 (재시도, 지수 백오프, 격리)](docs/Phase%206%20—%20DLQ%20+%20에러%20핸들링%20(재시도,%20지수%20백오프,%20격리).md) |
| 7 | [실전 선착순 쿠폰 발급 (동시성 테스트 + 전체 통합)](docs/Phase%207%20—%20실전%20선착순%20쿠폰%20발급%20(동시성%20테스트%20+%20전체%20통합).md) |

### 실험 기록 (7개)

| 문서 | 내용 |
|------|------|
| [Phase 1 히스토리](docs/Phase%201%20히스토리%20—%20클러스터%20구축%20과정%20기록.md) | 클러스터 구축 과정 기록 |
| [Phase 2 히스토리](docs/Phase%202%20히스토리%20—%20Producer%20심화%20실험%20기록.md) | Producer acks 벌크 1000건 실측 |
| [Phase 3 히스토리](docs/Phase%203%20히스토리%20—%20Consumer%20심화%20실험%20기록.md) | Consumer LAG 적체/복구 실측 |
| [Phase 5 실험 기록](docs/Phase%205%20실험%20기록%20—%20멱등성%20중복%20감지%20실측%20상세.md) | 10건 동일 eventId → 1건 처리 실측 |
| [Phase 6 실험 기록](docs/Phase%206%20실험%20기록%20—%20DLQ%20재시도와%20격리%20실측%20상세.md) | DLQ 재시도 + 지수 백오프 실측 |
| [Phase 7 실험 기록](docs/Phase%207%20실험%20기록%20—%20선착순%20쿠폰%20동시성%20테스트%20실측%20상세.md) | 200명/1000명 동시성 테스트 실측 |
| [Phase 7 실험 로그](docs/Phase%207%20실험%20로그%20—%20선착순%20쿠폰%20전체%20로그%20+%20DB%20상태%20+%20Kafka%20상태.md) | 전체 로그 + DB/Kafka 상태 스냅샷 |

### 트레이드오프 분석 (3개)

| 문서 | 핵심 |
|------|------|
| [Outbox Relay 동기 vs 비동기 vs CDC](docs/Outbox%20Relay%20동기%20vs%20비동기%20vs%20CDC%20—%20실무%20판단%20기준.md) | 3가지 방식 비교 + 실무 판단 기준 |
| [Outbox Relay 최종 트레이드오프](docs/Outbox%20Relay%20최종%20트레이드오프%20—%20동기%20Polling이%20맞는%20이유.md) | 동기 Polling 선택 근거 |
| [@Transactional 경계 문제 심화](docs/Outbox%20Relay의%20비동기%20vs%20동기%20—%20@Transactional%20경계%20문제%20심화.md) | 비동기 콜백이 TX 밖에서 실행되는 문제 |

### 설계 심화 + 멘토링 + 회고 (5개)

| 문서 | 내용 |
|------|------|
| [Kafka 토픽 구조 설계](docs/Kafka%20토픽%20구조%20설계%20—%20kafka-pipeline-lab.md) | 네이밍 컨벤션, 파티션 전략 |
| [멘토링 인사이트](docs/멘토링%20인사이트%20—%20Outbox%20원자성,%20팀별%20질문과%20케브님%20답변%20종합.md) | 7개 팀 질문 + 멘토 답변 종합 |
| [종합 회고 Phase 1~3](docs/종합%20회고%20—%20Phase%201~3%20(인프라%20→%20Producer%20→%20Consumer).md) | 인프라 → Producer → Consumer |
| [종합 회고 Phase 1~7](docs/종합%20회고%20—%20Phase%201~7%20전체%20(Kafka%20파이프라인%20완성).md) | Kafka 파이프라인 전체 완성 |
| [실행 가이드](docs/실행%20가이드%20—%20kafka-pipeline-lab%20전체%20명령어.md) | 전체 명령어 + 포트 정리 |

## 관련 프로젝트

| 프로젝트 | 범위 | GitHub |
|---------|------|--------|
| **kafka-pipeline-lab** (현재) | 단일 서비스 Kafka 패턴 — Outbox, 멱등성, DLQ, 선착순 쿠폰 | [iohyeon/kafka-pipeline-lab](https://github.com/iohyeon/kafka-pipeline-lab) |
| **kafka-eda-lab** | 멀티 서비스 EDA 패턴 — 코레오그래피, Saga, Kafka Streams | [iohyeon/kafka-eda-lab](https://github.com/iohyeon/kafka-eda-lab) |

## Security Notice

이 프로젝트는 **로컬 개발/학습 환경**을 위해 설계되었습니다.

- DB/Grafana 인증 정보는 환경변수로 분리 가능 (`.env.example` 참조)
- Kafka 통신은 PLAINTEXT (프로덕션에서는 SASL_SSL 필수)
- Actuator 엔드포인트는 인증 없이 노출 (프로덕션에서는 Spring Security 적용 필수)
- `show-sql: true`는 개발 편의용 (프로덕션에서는 비활성화)

프로덕션 배포 시 [Spring Boot Security Best Practices](https://docs.spring.io/spring-boot/reference/web/spring-security.html)를 참고하세요.

## Key Design Decisions

| 결정 | 선택 | 근거 |
|------|------|------|
| Broker 수 | 3 (KRaft) | 과반수 투표 가능한 최소 홀수, 1대 장애 허용 |
| acks | all | 주문/결제 도메인에서 메시지 유실은 치명적 |
| ACK 모드 | Manual | 처리 완료 후에만 offset 커밋, at-least-once 보장 |
| Outbox Relay | 동기 Polling | 안전성 우선, 병목 시 CDC 전환 경로 확보 |
| 멱등성 저장소 | DB (event_handled) | 비즈니스 TX와 원자적 롤백 필요, Redis SET NX의 재처리 불가 문제 회피 |
| 쿠폰 Partition Key | couponId | 같은 쿠폰 = 같은 파티션 = 순차 처리 = Lock-free 동시성 제어 |
| 재시도 전략 | 3회 + 지수 백오프 | 일시적 장애 복구 대기, DLQ로 영구 실패 격리 |
| CDC 인프라 | Debezium (검증 완료) | Outbox Polling → CDC 전환 경로의 실증. binlog → Kafka 자동 전달 확인 |
| 이벤트 발행 방식 | DIRECT_CALL (Outbox) | BEFORE_COMMIT/AFTER_COMMIT과 비교 후, 명시성과 디버깅 용이성 우선 |
