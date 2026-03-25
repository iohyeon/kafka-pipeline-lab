# 실행 가이드 — kafka-pipeline-lab 전체 명령어

## 프로젝트 경로
```
/Users/nahyeon/factory/kafka-pipeline-lab
```

---

## 1단계: Kafka 3-Broker 클러스터 기동

### 클러스터 시작
```bash
cd /Users/nahyeon/factory/kafka-pipeline-lab
docker compose -f docker/kafka-cluster-compose.yml up -d
```

### 클러스터 상태 확인
```bash
# 전체 서비스 상태 확인 (kafka-1, kafka-2, kafka-3, kafka-ui, mysql 전부 healthy/running인지)
docker compose -f docker/kafka-cluster-compose.yml ps

# 개별 브로커 로그 확인
docker logs kafka-1 --tail 20
docker logs kafka-2 --tail 20
docker logs kafka-3 --tail 20
```

### Kafka UI 접속
```
http://localhost:9099
```
→ 브라우저에서 열면 클러스터 상태, 토픽, 파티션, Consumer Group 전부 볼 수 있음

### 클러스터 중지
```bash
docker compose -f docker/kafka-cluster-compose.yml down
```

### 클러스터 + 볼륨까지 완전 삭제 (초기화)
```bash
docker compose -f docker/kafka-cluster-compose.yml down -v
```

---

## 2단계: 모니터링 스택 기동 (Prometheus + Grafana)

> 반드시 1단계(Kafka 클러스터)가 먼저 떠 있어야 함. 같은 Docker 네트워크를 공유하기 때문.

### 모니터링 시작
```bash
docker compose -f docker/monitoring-compose.yml up -d
```

### 접속 주소
| 서비스       | URL                     | 계정          |
|-------------|------------------------|--------------|
| Prometheus  | http://localhost:9090   | -            |
| Grafana     | http://localhost:3000   | admin / admin |

### Prometheus에서 메트릭 수집 확인
```
http://localhost:9090/targets
```
→ `kafka-pipeline-lab` 타겟이 `UP` 상태인지 확인 (앱이 떠 있어야 함)

### Grafana에서 Kafka 대시보드 확인
1. http://localhost:3000 접속 → admin/admin 로그인
2. 좌측 메뉴 → Dashboards → New → Import
3. Dashboard ID 입력: `18276` (Spring Boot + Kafka 대시보드) → Load → Prometheus 선택 → Import
4. 또는 `7589` (Spring Boot Actuator 전용)

### 모니터링 중지
```bash
docker compose -f docker/monitoring-compose.yml down
```

---

## 3단계: Spring Boot 애플리케이션 실행

### 빌드
```bash
cd /Users/nahyeon/factory/kafka-pipeline-lab
./gradlew build --no-daemon
```

### 실행
```bash
./gradlew bootRun --no-daemon
```
→ 포트: `http://localhost:8085`

### Actuator 메트릭 확인
```bash
# 헬스 체크
curl http://localhost:8085/actuator/health | jq

# Prometheus 메트릭 (Kafka 관련 포함)
curl http://localhost:8085/actuator/prometheus | grep kafka
```

---

## 4단계: 메시지 발행 테스트

### 단건 발행
```bash
# orderId=54333으로 주문 이벤트 발행
curl -X POST http://localhost:8085/api/test/order/54333

# 같은 orderId로 다시 발행 → 같은 파티션에 들어가는지 Kafka UI에서 확인
curl -X POST http://localhost:8085/api/test/order/54333
```

### 다건 벌크 발행
```bash
# 10건 발행 — 파티션 분배 확인용
curl -X POST http://localhost:8085/api/test/orders/bulk/10

# 100건 발행
curl -X POST http://localhost:8085/api/test/orders/bulk/100
```

---

## 5단계: Kafka CLI로 직접 확인

### 토픽 목록 조회
```bash
docker exec kafka-1 kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

### 토픽 상세 조회 (파티션, 복제본, ISR 확인)
```bash
docker exec kafka-1 kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic order.created.event-v1
```
→ 출력 예시:
```
Topic: order.created.event-v1   Partition: 0   Leader: 1   Replicas: 1,2,3   Isr: 1,2,3
Topic: order.created.event-v1   Partition: 1   Leader: 2   Replicas: 2,3,1   Isr: 2,3,1
Topic: order.created.event-v1   Partition: 2   Leader: 3   Replicas: 3,1,2   Isr: 3,1,2
```

### Consumer Group 목록 조회
```bash
docker exec kafka-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list
```

### Consumer Group 상세 (offset, lag 확인)
```bash
docker exec kafka-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group order-inventory-group
```
→ 각 파티션별 CURRENT-OFFSET, LOG-END-OFFSET, LAG 확인 가능

### 메시지 직접 소비 (콘솔 Consumer)
```bash
# 처음부터 읽기
docker exec kafka-1 kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.created.event-v1 \
  --from-beginning

# 특정 파티션만 읽기
docker exec kafka-1 kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.created.event-v1 \
  --partition 0 \
  --from-beginning
```

### 메시지 직접 발행 (콘솔 Producer)
```bash
docker exec -it kafka-1 kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.created.event-v1 \
  --property "parse.key=true" \
  --property "key.separator=:"
```
→ 입력 예시: `54333:{"orderId":54333,"userId":1}`

---

## 6단계: 장애 시나리오 테스트

### 브로커 1대 죽이기
```bash
docker stop kafka-2
```

### 토픽 상태 확인 — ISR 변화 관찰
```bash
docker exec kafka-1 kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic order.created.event-v1
```
→ ISR에서 Broker 2가 빠지는지 확인

### 메시지 발행 — 브로커 1대 죽은 상태에서도 되는지
```bash
curl -X POST http://localhost:8085/api/test/order/99999
```
→ ISR 2개 → min.insync.replicas=2 충족 → 성공해야 함

### 브로커 2대 죽이기
```bash
docker stop kafka-3
```
→ ISR 1개 → min.insync.replicas=2 미충족 → **쓰기 실패 예상** (NotEnoughReplicasException)

### 브로커 복구
```bash
docker start kafka-2
docker start kafka-3
```

---

## 전체 시작/종료 한 번에

### 전체 시작 (순서 중요: Kafka 먼저 → 모니터링 → 앱)
```bash
cd /Users/nahyeon/factory/kafka-pipeline-lab

# 1) Kafka 클러스터 + MySQL
docker compose -f docker/kafka-cluster-compose.yml up -d

# 2) 모니터링
docker compose -f docker/monitoring-compose.yml up -d

# 3) 앱 실행
./gradlew bootRun --no-daemon
```

### 전체 종료
```bash
# 앱은 Ctrl+C로 종료

# 모니터링 종료
docker compose -f docker/monitoring-compose.yml down

# Kafka 클러스터 종료
docker compose -f docker/kafka-cluster-compose.yml down
```

### 전체 초기화 (볼륨까지 삭제)
```bash
docker compose -f docker/monitoring-compose.yml down -v
docker compose -f docker/kafka-cluster-compose.yml down -v
```

---

## 포트 정리

| 서비스          | 포트    | 용도                    |
|----------------|--------|------------------------|
| kafka-1        | 19092  | Broker 1 (Host 접근용)  |
| kafka-2        | 29092  | Broker 2 (Host 접근용)  |
| kafka-3        | 39092  | Broker 3 (Host 접근용)  |
| Kafka UI       | 9099   | 클러스터 관리 UI         |
| MySQL          | 3307   | Outbox용 DB             |
| Prometheus     | 9090   | 메트릭 수집             |
| Grafana        | 3000   | 모니터링 대시보드         |
| Spring Boot 앱 | 8085   | API 서버               |

> 참고 프로젝트(loop-pack)와 포트 충돌 없도록 전부 다른 포트 사용
