# 심화 — CDC (Debezium) 인프라 검증

## 개요

Phase 4에서 Outbox 패턴을 **동기 Polling Relay**로 구현했다. 이것의 궁극적 전환 경로가 **CDC(Change Data Capture)** — Relay 코드 없이 DB의 binlog 변경을 자동으로 Kafka에 전달하는 방식이다.

이 프로젝트에서는 Debezium을 Docker Compose에 구축하여 **"Outbox INSERT → binlog → Kafka 토픽 자동 전달"** 을 실증했다.

---

## 현재 방식 vs CDC 방식

### 현재: Outbox Polling Relay

```
[Spring App]                           [Kafka]
   │                                      │
   ├── @Transactional                     │
   │   ├── 주문 저장                       │
   │   └── Outbox INSERT (PENDING)        │
   │                                      │
   │   [5초 간격 @Scheduled]               │
   │   └── SELECT * FROM outbox_event     │
   │       WHERE status = 'PENDING'       │
   │       → KafkaTemplate.send().get()   ──→ 토픽에 메시지 도착
   │       → UPDATE status = 'PUBLISHED'  │
```

**문제점**:
- Polling 간격(5초)만큼 지연 발생
- DB 부하 — 주기적 SELECT 쿼리
- Relay 코드 유지보수 필요 (재시도, 클린업 등)

### CDC 방식: Debezium

```
[Spring App]                    [MySQL]                [Debezium]           [Kafka]
   │                              │                       │                   │
   ├── @Transactional             │                       │                   │
   │   ├── 주문 저장               │                       │                   │
   │   └── Outbox INSERT ────────→│ binlog 기록            │                   │
   │                              │                       │                   │
   │                              │ binlog ──────────────→│                   │
   │                              │  (Slave 위장)          │                   │
   │                              │                       ├── INSERT 감지      │
   │                              │                       └── Kafka 발행 ────→│ 토픽에 메시지 도착
```

**Relay 코드가 사라진다.** Debezium이 MySQL의 Slave로 위장하여 binlog를 실시간 수신하고, INSERT/UPDATE/DELETE를 Kafka 토픽에 자동 전달.

---

## 인프라 구성 (docker-compose)

### MySQL — binlog 활성화

```yaml
mysql:
  command:
    - --binlog-format=ROW           # CDC 필수 — 행 단위 변경 기록
    - --binlog-row-image=FULL       # 변경 전/후 전체 컬럼 기록
    - --server-id=1                 # Slave(Debezium)가 연결하기 위한 ID
    - --log-bin=mysql-bin           # binlog 파일 활성화
    - --gtid-mode=ON                # Global Transaction ID (Debezium 권장)
    - --enforce-gtid-consistency=ON # GTID 일관성 강제
```

**핵심 설정 설명**:
- `binlog-format=ROW`: STATEMENT(SQL문 기록)가 아닌 **행 변경 데이터**를 기록. CDC는 "어떤 행이 어떻게 바뀌었는지"가 필요하므로 ROW 필수.
- `binlog-row-image=FULL`: 변경 전(before) + 변경 후(after) 전체 컬럼을 기록. MINIMAL로 하면 PK와 변경된 컬럼만 기록되어 정보가 부족할 수 있음.
- `server-id=1`: MySQL 복제 프로토콜에서 각 노드를 구분하는 ID. Debezium이 Slave로 연결하려면 Master에 server-id가 설정되어 있어야 한다.
- `gtid-mode=ON`: 트랜잭션에 글로벌 고유 ID를 부여. Debezium이 binlog 위치 추적 시 파일명+오프셋 대신 GTID로 정확한 위치를 찾을 수 있다.

### Debezium — Kafka Connect + MySQL Connector

```yaml
debezium:
  image: debezium/connect:2.5
  ports:
    - "8084:8083"                          # Connect REST API
  environment:
    BOOTSTRAP_SERVERS: "kafka-1:9092,kafka-2:9092,kafka-3:9092"
    GROUP_ID: "debezium-connect-group"
    CONFIG_STORAGE_TOPIC: "debezium-configs"     # Connector 설정 저장
    OFFSET_STORAGE_TOPIC: "debezium-offsets"     # binlog 읽기 위치 저장
    STATUS_STORAGE_TOPIC: "debezium-status"      # Connector 상태 저장
    CONFIG_STORAGE_REPLICATION_FACTOR: "3"
    OFFSET_STORAGE_REPLICATION_FACTOR: "3"
    STATUS_STORAGE_REPLICATION_FACTOR: "3"
    KEY_CONVERTER: "org.apache.kafka.connect.json.JsonConverter"
    VALUE_CONVERTER: "org.apache.kafka.connect.json.JsonConverter"
    KEY_CONVERTER_SCHEMAS_ENABLE: "false"
    VALUE_CONVERTER_SCHEMAS_ENABLE: "false"
```

**Debezium의 내부 동작**:
1. Kafka Connect 프레임워크 위에서 동작 (Debezium은 Source Connector 구현체)
2. MySQL에 **SHOW MASTER STATUS** → 현재 binlog 위치 확인
3. **SHOW SLAVE STATUS** 프로토콜로 binlog 이벤트 스트림 수신
4. 각 INSERT/UPDATE/DELETE를 Kafka 메시지로 변환하여 발행
5. 읽기 위치(offset)를 `debezium-offsets` 토픽에 저장 → 재시작 시 이어서 읽기

---

## Connector 등록 — Outbox 테이블 전용

```bash
# Debezium MySQL Connector 등록 (Outbox Event Routing SMT 적용)
curl -X POST http://localhost:8084/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "outbox-connector",
    "config": {
      "connector.class": "io.debezium.connector.mysql.MySqlConnector",
      "database.hostname": "mysql",
      "database.port": "3306",
      "database.user": "root",
      "database.password": "root",
      "database.server.id": "184054",
      "database.server.name": "pipeline-db",
      "database.include.list": "pipeline",
      "table.include.list": "pipeline.outbox_event",
      "transforms": "outbox",
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType",
      "transforms.outbox.route.topic.replacement": "${routedByValue}",
      "transforms.outbox.table.field.event.id": "id",
      "transforms.outbox.table.field.event.key": "partition_key",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.route.by.field": "topic"
    }
  }'
```

**Outbox Event Router (SMT)** 가 핵심:
- `table.include.list`: outbox_event 테이블만 감시
- `transforms.outbox.type`: Debezium의 Outbox Event Router SMT(Single Message Transform)
- `route.by.field: "topic"`: outbox_event의 `topic` 컬럼 값을 **목적지 Kafka 토픽명**으로 사용
- `table.field.event.key: "partition_key"`: `partition_key` 컬럼을 Kafka 메시지 키로 사용
- `table.field.event.payload: "payload"`: `payload` 컬럼(JSON)을 메시지 본문으로 사용

→ outbox_event에 INSERT하면, Debezium이 해당 행의 `topic` 필드에 적힌 Kafka 토픽으로 `payload`를 자동 전달.

---

## Polling Relay vs CDC 최종 비교

| 기준 | Polling Relay (현재) | CDC (Debezium) |
|------|---------------------|----------------|
| 지연 | 5초 (polling 간격) | 수백 ms (거의 실시간) |
| DB 부하 | SELECT 쿼리 반복 | binlog 읽기 (가벼움) |
| 코드 | OutboxRelayService 유지보수 필요 | Connector JSON 설정만 |
| 인프라 | 없음 (앱 내부) | Kafka Connect 클러스터 필요 |
| 복잡도 | 낮음 | 높음 (Connect + Debezium + SMT) |
| 장애 복구 | retry_count + FAILED 상태 | offset 토픽에서 자동 복구 |
| 운영 | 코드 디버깅 | Connector 모니터링 |

**이 프로젝트의 판단**: 학습 단계에서는 Polling Relay의 명시성이 더 가치 있다. 하지만 프로덕션에서 지연이 문제가 되면 CDC로 전환할 수 있음을 인프라 레벨에서 실증했다.

---

## 검증 결과

1. Docker Compose로 3-Broker KRaft + MySQL(binlog) + Debezium 구동 확인
2. Debezium이 MySQL binlog에 연결되어 변경 이벤트를 수신하는 것 확인
3. Outbox Event Router SMT로 outbox_event INSERT → 지정된 Kafka 토픽 자동 전달 확인
4. **Relay 코드 없이** 동일한 결과 달성

---

## 관련 문서

- [Outbox Relay 동기 vs 비동기 vs CDC — 실무 판단 기준](Outbox%20Relay%20동기%20vs%20비동기%20vs%20CDC%20—%20실무%20판단%20기준.md)
- [Phase 4 — Transactional Outbox 패턴 구현](Phase%204%20—%20Transactional%20Outbox%20패턴%20구현.md)
- [Outbox Relay 최종 트레이드오프](Outbox%20Relay%20최종%20트레이드오프%20—%20동기%20Polling이%20맞는%20이유.md)
