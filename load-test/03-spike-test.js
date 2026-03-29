import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * 시나리오 3: 스파이크 테스트 (Spike Load)
 *
 * 목적: 갑작스러운 트래픽 급증 시 시스템 반응 측정
 * - Consumer Lag가 급증했다가 복구되는 과정을 Grafana에서 관찰
 * - 에러율이 증가하는 시점 식별 (시스템 한계점)
 * - 스파이크 후 시스템이 정상으로 돌아오는 복구 시간 측정
 *
 * 흐름:
 * Normal(1min) → SPIKE(30s, 10배 트래픽) → Recovery(1min) → SPIKE(30s) → Cool-down(30s)
 *
 * 이 패턴은 실무에서 자주 발생:
 * - 타임세일 시작
 * - 푸시 알림 발송 직후
 * - 점심시간 주문 몰림
 */

const errorRate = new Rate('errors');
const spikeLatency = new Trend('spike_latency', true);

export const options = {
    stages: [
        // Phase 1: Normal — 기준선 측정
        { duration: '1m', target: 20 },

        // Phase 2: SPIKE — 10배 급증 (2초 만에)
        { duration: '2s', target: 200 },
        { duration: '28s', target: 200 },

        // Phase 3: Recovery — 트래픽 급감, 시스템 복구 관찰
        { duration: '5s', target: 20 },
        { duration: '55s', target: 20 },

        // Phase 4: 2nd SPIKE — 시스템이 완전 복구된 후 다시 스파이크
        { duration: '2s', target: 200 },
        { duration: '28s', target: 200 },

        // Phase 5: Cool-down
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        // 스파이크 테스트는 임계값을 느슨하게 설정 (의도적으로 한계를 찾는 것이므로)
        http_req_duration: ['p(95)<2000'],  // p95 < 2초
        errors: ['rate<0.10'],               // 에러율 < 10%
    },
};

const BASE_URL = 'http://localhost:8085';

export default function () {
    const userId = Math.floor(Math.random() * 1000) + 1;
    const amount = Math.floor(Math.random() * 50000) + 10000;

    const res = http.post(
        `${BASE_URL}/api/experiment/outbox/order?userId=${userId}&amount=${amount}`
    );

    const success = check(res, {
        'status is 200': (r) => r.status === 200,
    });

    errorRate.add(!success);
    spikeLatency.add(res.timings.duration);

    sleep(0.05); // 50ms 간격 (스파이크 시 VU당 ~20 req/s)
}

export function handleSummary(data) {
    const summary = {
        scenario: 'Spike Test',
        vus_max: 200,
        duration: '4m30s',
        total_requests: data.metrics.http_reqs.values.count,
        rps: data.metrics.http_reqs.values.rate.toFixed(2),
        latency: {
            avg: data.metrics.http_req_duration.values.avg.toFixed(2) + 'ms',
            p95: data.metrics.http_req_duration.values['p(95)'].toFixed(2) + 'ms',
            p99: data.metrics.http_req_duration.values['p(99)'].toFixed(2) + 'ms',
            max: data.metrics.http_req_duration.values.max.toFixed(2) + 'ms',
        },
        error_rate: (data.metrics.errors.values.rate * 100).toFixed(2) + '%',
    };

    return {
        'stdout': JSON.stringify(summary, null, 2) + '\n',
        'load-test/results/03-spike-test-result.json': JSON.stringify(summary, null, 2),
    };
}
