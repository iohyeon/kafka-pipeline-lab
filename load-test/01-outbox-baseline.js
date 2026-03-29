import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * 시나리오 1: Outbox 패턴 기본 부하 (Steady Load)
 *
 * 목적: Outbox 주문 생성 API의 기본 처리 능력 측정
 * - TPS (초당 처리량)
 * - Latency (응답 시간 분포)
 * - Error Rate (에러 비율)
 *
 * 흐름:
 * Ramp-up(30s) → Steady(2min) → Ramp-down(30s)
 * 최대 50 VUs (Virtual Users)
 */

const errorRate = new Rate('errors');
const orderLatency = new Trend('order_latency', true);

export const options = {
    stages: [
        { duration: '30s', target: 10 },   // Warm-up: 10 VUs
        { duration: '30s', target: 30 },   // Ramp-up: 30 VUs
        { duration: '2m', target: 50 },    // Steady: 50 VUs (2분 유지)
        { duration: '30s', target: 0 },    // Ramp-down
    ],
    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],  // p95 < 500ms, p99 < 1s
        errors: ['rate<0.01'],                             // 에러율 < 1%
    },
};

const BASE_URL = 'http://localhost:8085';

export default function () {
    // 주문 생성 (Outbox 패턴)
    const userId = Math.floor(Math.random() * 1000) + 1;
    const amount = Math.floor(Math.random() * 50000) + 10000;

    const res = http.post(
        `${BASE_URL}/api/experiment/outbox/order?userId=${userId}&amount=${amount}`
    );

    const success = check(res, {
        'status is 200': (r) => r.status === 200,
        'has orderId': (r) => JSON.parse(r.body).orderId !== undefined,
    });

    errorRate.add(!success);
    orderLatency.add(res.timings.duration);

    sleep(0.1); // 100ms 간격 (VU당 ~10 req/s)
}

export function handleSummary(data) {
    const summary = {
        scenario: 'Outbox Baseline',
        vus_max: 50,
        duration: '3m30s',
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
        'load-test/results/01-outbox-baseline-result.json': JSON.stringify(summary, null, 2),
    };
}
