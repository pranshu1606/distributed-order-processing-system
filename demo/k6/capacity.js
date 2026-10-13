import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// One iteration = one shopper checkout: a cached stock read, then an order.
// constant-arrival-rate starts checkouts at RATE/s whether or not earlier ones
// finished, like real traffic. VUs are only the generator's worker pool.
const products = JSON.parse(open('/data/k6-capacity-input.json')).products;
const rate = Number(__ENV.RATE);
const ordersOnly = __ENV.ORDERS_ONLY === '1'; // chaos tests: orders hit a stalled dependency directly
const clientRetries = __ENV.RETRIES === '1';   // behave like a careful client: retry 503/timeouts safely
const runId = __ENV.RUN_ID || 'local';
if (!Number.isInteger(rate) || rate < 1 || rate > 2000) throw new Error('RATE must be 1-2000 checkouts/s');

const created = new Counter('orders_created');
const shed = new Counter('orders_shed');          // 503: the backend refused extra work quickly
const timedOut = new Counter('orders_timed_out'); // no response within the client timeout
const unexpected = new Counter('unexpected_responses');
const orderOk = new Rate('order_success_rate');
const retried = new Counter('order_retries');

export const options = {
  scenarios: {
    checkout: {
      executor: 'constant-arrival-rate',
      rate, timeUnit: '1s', duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(rate * 4, 600),
      maxVUs: Math.min(rate * 6, 1200),
      gracefulStop: '10s',
    },
  },
  // Service-level objective for one stage; the runner stops at the first failure.
  thresholds: {
    'http_req_duration{name:capacity_order}': ['p(95)<1000'],
    ...(ordersOnly ? {} : { 'http_req_duration{name:capacity_read}': ['p(95)<500'] }),
    order_success_rate: ['rate>0.99'],
    unexpected_responses: ['count==0'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const product = products[Math.floor(Math.random() * products.length)];
  if (!ordersOnly) {
    const read = http.get(`http://inventory-service:8081/inventory/${product.productId}`, {
      tags: { name: 'capacity_read' }, timeout: '5s',
    });
    check(read, { 'read HTTP 200': (r) => r.status === 200 });
  }

  // One key per checkout: every retry of this checkout reuses it, so the server
  // returns the original order instead of creating a duplicate.
  const key = `${runId}-${rate}-${__VU}-${__ITER}`; // unique across stages and VUs
  let order;
  for (let attempt = 1; ; attempt++) {
    order = http.post('http://order-service:8080/orders',
      JSON.stringify({ productId: product.productId, quantity: 1 }), {
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
        tags: { name: 'capacity_order' }, timeout: '5s',
        responseCallback: http.expectedStatuses(200, 503),
      });
    if (!clientRetries || attempt >= 3 || (order.status !== 503 && order.status !== 0)) break;
    retried.add(1);
    sleep(1 + Math.random()); // Retry-After: 1, plus jitter so retries do not arrive in lockstep
  }
  orderOk.add(order.status === 200);
  if (order.status === 200) created.add(1);
  else if (order.status === 503) shed.add(1);
  else if (order.status === 0) timedOut.add(1);
  else unexpected.add(1);
  if (order.status !== 200 && order.status !== 503 && order.status !== 0) {
    console.warn(`unexpected order status ${order.status}: ${String(order.body).slice(0, 160)}`);
  }
}

export function handleSummary(data) {
  return {
    [`/reports/k6-capacity-${rate}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: `${rate} checkouts/s stage finished; metrics saved.\n`,
  };
}
