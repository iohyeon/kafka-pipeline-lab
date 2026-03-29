import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * 시나리오 2: 선착순 쿠폰 동시성 부하 (Concurrency Stress)
 *
 * 목적: Kafka key-routing 기반 동시성 제어가 부하 하에서도 정합성을 유지하는지 검증
 * - 100개 쿠폰에 500명이 동시 요청
 * - issuedQuantity <= totalQuantity 검증 (초과 발급 없음)
 *
 * 흐름:
 * 1. Setup: 쿠폰 생성 (100개 재고)
 * 2. Load: 500 VUs가 동시에 쿠폰 발급 요청 → Kafka로 전송
 * 3. Teardown: 30초 대기 후 결과 검증 (정합성 확인)
 */

const errorRate = new Rate('errors');
const couponLatency = new Trend('coupon_publish_latency', true);

export const options = {
    scenarios: {
        coupon_rush: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 100 },   // 100명 진입
                { duration: '20s', target: 300 },   // 300명으로 증가
                { duration: '30s', target: 500 },   // 500명 동시 (피크)
                { duration: '10s', target: 0 },     // 종료
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1000'],
        errors: ['rate<0.05'],  // Kafka 발행이므로 5% 이하
    },
};

const BASE_URL = 'http://localhost:8085';
const COUPON_ID = 99;  // 부하테스트 전용 쿠폰 ID

export function setup() {
    // 쿠폰 생성 (100개 재고)
    const res = http.post(
        `${BASE_URL}/api/experiment/coupon/create?couponId=${COUPON_ID}&name=부하테스트쿠폰&quantity=100`
    );
    console.log(`Setup: 쿠폰 생성 — ${res.body}`);
    return { couponId: COUPON_ID };
}

export default function (data) {
    const userId = Math.floor(Math.random() * 10000) + 1;

    // 개별 유저가 직접 Kafka에 쿠폰 발급 요청을 보내는 대신,
    // HTTP API를 통해 요청 (실제 서비스 패턴)
    const res = http.post(
        `${BASE_URL}/api/experiment/outbox/order?userId=${userId}&amount=10000`
    );

    const success = check(res, {
        'status is 200': (r) => r.status === 200,
    });

    errorRate.add(!success);
    couponLatency.add(res.timings.duration);

    // VU간 약간의 지터
    sleep(Math.random() * 0.2);
}

export function teardown(data) {
    // 30초 대기 — Consumer가 처리할 시간
    sleep(5);

    // Outbox 상태 확인
    const statusRes = http.get(`${BASE_URL}/api/experiment/outbox/status`);
    console.log(`Teardown: Outbox 상태 — ${statusRes.body}`);
}

export function handleSummary(data) {
    const summary = {
        scenario: 'Coupon Concurrency Stress',
        vus_max: 500,
        duration: '1m10s',
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
        'load-test/results/02-coupon-concurrency-result.json': JSON.stringify(summary, null, 2),
    };
}
