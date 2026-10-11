import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const input = JSON.parse(open('/data/k6-input.json'));
const mode = __ENV.MODE;
if (!['normal', 'contention'].includes(mode)) throw new Error('MODE must be normal or contention');
const products = input[mode];
const created = new Counter('orders_created');
const rejected = new Counter('stock_rejections');
const unexpected = new Counter('unexpected_responses');

export const options = {
  scenarios: mode === 'normal'
    ? { normal: { executor: 'constant-vus', vus: 10, duration: '30s', gracefulStop: '15s' } }
    : { contention: { executor: 'per-vu-iterations', vus: 30, iterations: 1, maxDuration: '30s' } },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    unexpected_responses: ['count==0'],
    orders_created: mode === 'normal' ? ['count>0'] : ['count==15'],
    ...(mode === 'normal' ? {
      'http_req_duration{name:normal_create_order}': ['p(95)<2000'],
      'http_req_duration{name:normal_inventory_read}': ['p(95)<1000'],
    } : { stock_rejections: ['count==15'] }),
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

export default function () {
  const product = products[mode === 'normal' ? (__VU + __ITER) % products.length : (__VU - 1) % products.length];
  if (mode === 'normal') {
    const response = http.get(`http://inventory-service:8081/inventory/${product.productId}`, {
      tags: { name: 'normal_inventory_read' }, timeout: '10s',
    });
    check(response, { 'inventory read succeeds': (r) => r.status === 200 && r.json().productId === product.productId });
  }
  const response = http.post('http://order-service:8080/orders', JSON.stringify({
    productId: product.productId, quantity: 1,
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: `${mode}_create_order` }, timeout: '10s',
    // 409 is a correct business outcome only in the contention scenario.
    responseCallback: mode === 'contention' ? http.expectedStatuses(200, 409) : http.expectedStatuses(200),
  });
  created.add(response.status === 200 ? 1 : 0);
  rejected.add(response.status === 409 ? 1 : 0);
  unexpected.add(response.status !== 200 && !(mode === 'contention' && response.status === 409) ? 1 : 0);
  check(response, {
    'order response is correct': (r) => r.status === 200
      ? r.json().status === 'CREATED' && r.json().productId === product.productId && r.json().quantity === 1
      : mode === 'contention' && r.status === 409 && r.body.includes('Not enough inventory'),
  });
  if (mode === 'normal') sleep(0.2);
}

export function handleSummary(data) {
  return {
    [`/reports/k6-${mode}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: `${mode} complete. Full metrics: demo/artifacts/k6-${mode}-summary.json\n`,
  };
}
