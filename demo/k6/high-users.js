import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const products = JSON.parse(open('/data/k6-high-users-input.json')).products;
const users = Number(__ENV.USERS);
if (![100, 1000, 3000, 6000, 12000].includes(users)) throw new Error('Unsupported user stage');
const created = new Counter('orders_created');
const unexpected = new Counter('unexpected_responses');

function matchesProduct(response, productId, isOrder) {
  if (response.status !== 200) return false;
  try {
    const body = response.json();
    return body.productId === productId && Number.isInteger(body.quantity)
      && (isOrder ? body.quantity === 1 && body.status === 'CREATED' && body.id > 0 : body.quantity >= 0);
  } catch (_) {
    return false;
  }
}

export const options = {
  scenarios: { traffic: { executor: 'constant-vus', vus: users, duration: __ENV.DURATION || '10s', gracefulStop: '10s' } },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    orders_created: ['count>0'],
    unexpected_responses: ['count==0'],
    'http_req_duration{name:high_users_order}': ['p(95)<2000'],
    'http_req_duration{name:high_users_read}': ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};

export default function () {
  const product = products[(__VU + __ITER) % products.length];
  const read = http.get(`http://inventory-service:8081/inventory/${product.productId}`, {
    tags: { name: 'high_users_read' }, timeout: '5s',
  });
  check(read, {
    'inventory HTTP 200': (r) => r.status === 200,
    'inventory payload matches product': (r) => matchesProduct(r, product.productId, false),
  });
  const order = http.post('http://order-service:8080/orders', JSON.stringify({productId: product.productId, quantity: 1}), {
    headers: {'Content-Type': 'application/json'}, tags: {name: 'high_users_order'}, timeout: '5s',
  });
  created.add(order.status === 200 ? 1 : 0);
  unexpected.add(order.status !== 200 ? 1 : 0);
  check(order, {
    'order HTTP 200': (r) => r.status === 200,
    'order payload matches request': (r) => matchesProduct(r, product.productId, true),
  });
  sleep(0.2);
}

export function handleSummary(data) {
  return {
    [`/reports/k6-users-${users}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: `${users}-user stage finished; metrics saved.\n`,
  };
}
