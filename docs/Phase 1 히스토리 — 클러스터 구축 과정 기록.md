# Phase 1 히스토리 — 클러스터 구축 과정 기록

---

## 설계 단계

### 의사결정 1: 왜 3 Broker인가?

```
선택지:
  A. 1 Broker (기존 프로젝트 방식) — 간단, 리소스 적음
  B. 3 Broker (실무 최소 구성) — 복제, 장애 복구 가능
  C. 5 Broker — 대규모, 2대 죽어도 운영

선택: B (3 Broker)

근거:
  - 과반수 투표가 가능한 최소 홀수
  - Replication Factor 3 + Min ISR 2 → 1대 죽어도 쓰기 가능
  - 로컬 Docker에서 5대는 리소스 과다
  - 실무에서도 소규모 서비스는 3 Broker로 시작
```

### 의사결정 2: KRaft vs ZooKeeper

```
선택: KRaft

근거:
  - Kafka 3.3+ 기본
  - ZooKeeper 별도 관리 불필요
  - 기존 프로젝트(loop-pack)도 KRaft 사용 중
  - 브로커가 Controller 역할 겸임 → 아키텍처 단순화
```

### 의사결정 3: 포트 설계

```
기존 프로젝트와 포트 충돌을 피해야 했다.

기존 프로젝트       kafka-pipeline-lab
─────────────       ──────────────────
Kafka: 19092        Broker1: 19092  ← 충돌 가능! 기존 프로젝트 꺼야 함
MySQL: 3306         MySQL: 3307
Kafka UI: 9099      Kafka UI: 9099  ← 충돌 가능!
앱: 8081            앱: 8085

교훈: 동시 실행하려면 포트를 전부 다르게 잡아야 한다.
현재는 기존 프로젝트 인프라를 내린 상태에서 실행.
```

---

## 구축 과정에서 발생한 문제들

### 문제 1: Docker 이미지 not found

```
시도: bitnami/kafka:3.7.0
결과: failed to resolve reference "docker.io/bitnami/kafka:3.7.0": not found

원인: bitnami가 Docker Hub에서 kafka 이미지를 bitnamilegacy로 이전함.
      bitnami/kafka는 더 이상 존재하지 않음.

해결: bitnamilegacy/kafka:3.5.1로 변경 (기존 프로젝트에서 사용하던 이미지)

교훈: Docker 이미지는 레지스트리 변경, deprecated 될 수 있다.
      기존에 동작하는 이미지를 먼저 확인하고 쓰는 게 안전하다.
```

### 문제 2: Cluster ID UUID 형식 오류

```
시도: KAFKA_KRAFT_CLUSTER_ID: "kafka-pipeline-lab-cluster-001"
결과: Cluster ID string kafka-pipeline-lab-clust does not appear to be a valid UUID:
      Input string with prefix `kafka-pipeline-lab-clust` is too long to be decoded as a base64 UUID

원인: KRaft의 Cluster ID는 base64 UUID 형식이어야 한다.
      사람이 읽기 좋은 문자열은 안 됨.

해결: KAFKA_KRAFT_CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk" (base64 UUID)

교훈: KRaft Cluster ID는 kafka-storage.sh random-uuid로 생성하거나
      22자의 base64 문자열을 사용해야 한다.
      docker compose down -v로 볼륨까지 삭제 후 재시작 필요.
```

### 문제 3: version 속성 경고

```
경고: the attribute `version` is obsolete, it will be ignored

원인: Docker Compose V2에서는 version 속성이 불필요.
상태: 경고만 뜨고 동작에는 문제없음. 제거해도 됨.
```

### 문제 4: Orphan containers 경고

```
경고: Found orphan containers ([redis-master, kafka, ...]) for this project

원인: 같은 docker/ 디렉토리에서 다른 compose 파일의 컨테이너가 남아있음.
      기존 프로젝트의 컨테이너들.

해결: --remove-orphans 플래그 사용 가능하나,
      기존 프로젝트 컨테이너를 실수로 지울 수 있어서 무시함.
```

---

## 설계 완료 후 검증 결과

```
검증 항목                                결과
─────────────────────────────────────────────────
3 Broker 전부 healthy                    ✅
Kafka UI 접속 (localhost:9099)           ✅
MySQL 접속 (localhost:3307)              ✅
토픽 4개 자동 생성                        ✅
  - order.created.event-v1               partition:3, replicas:3, ISR:3
  - coupon.issue.request-v1              partition:3, replicas:3, ISR:3
  - inventory.deduct.event-v1            partition:3, replicas:3, ISR:3
  - pipeline.dlq-v1                      partition:1, replicas:3, ISR:3
Leader 분배 (자동)                        Broker 1,2,3에 균등 분배
Spring Boot 앱 기동                       ✅ (port 8085)
메시지 발행 (14건)                        ✅
Consumer 소비 (LAG=0)                    ✅
Prometheus 메트릭 수집                    ✅ (kafka_* 메트릭 확인)
Grafana 접속                             ✅ (admin/admin)
```

---

## 트레이드오프

```
┌──────────────────────┬───────────────────────────┬──────────────────────────┐
│ 항목                  │ 선택                       │ 포기한 것                │
├──────────────────────┼───────────────────────────┼──────────────────────────┤
│ Broker 수             │ 3 (실무 최소)              │ 리소스 절약 (1대)        │
│ Replication Factor    │ 3 (모든 Broker에 복제)     │ 디스크 사용량 3배        │
│ Min ISR               │ 2                         │ 2대 죽으면 쓰기 불가     │
│ KRaft (ZK 없음)       │ 단순한 아키텍처            │ ZK 기반 문서/도구 호환성 │
│ 이미지 (bitnamilegacy)│ 기존 프로젝트와 동일       │ 최신 Kafka 버전 (3.7+)   │
└──────────────────────┴───────────────────────────┴──────────────────────────┘
```
