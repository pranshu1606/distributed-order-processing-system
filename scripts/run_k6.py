"""Run bounded k6 tests on the fixed isolated demo; verify PostgreSQL afterward."""
import json
import pathlib
import subprocess
import time
from datetime import datetime, timezone

from external_api_demo import request, database_rows, SOURCE_URL, INVENTORY_URL, ORDER_URL

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / 'demo' / 'artifacts'
IMAGE = 'grafana/k6:2.3.0'


def main():
    for url in (INVENTORY_URL, ORDER_URL):
        response = request('GET', url + '/actuator/health')
        if response['status'] != 200 or response['body'].get('status') != 'UP':
            raise RuntimeError('Start the portfolio-demo stack first.')
    print('Fetching one three-product catalog sample, then seeding isolated demo stock...', flush=True)
    source = request('GET', SOURCE_URL)
    if source['status'] != 200 or not isinstance(source['body'], dict):
        raise RuntimeError('External catalog fetch failed.')
    products = source['body'].get('products')
    if not isinstance(products, list) or len(products) != 3 or any(
        not isinstance(p, dict) or not isinstance(p.get('id'), int)
        or not isinstance(p.get('title'), str) or not p['title'].strip() for p in products
    ):
        raise RuntimeError('Invalid external catalog: no inventory written.')
    base_id = time.time_ns() // 1_000_000 * 1000
    data = {'normal': [], 'contention': []}
    for mode, stock, offset in [('normal', 10000, 0), ('contention', 5, 3)]:
        for index, product in enumerate(products):
            local = {'productId': base_id + offset + index, 'productName': product['title'], 'quantity': stock}
            response = request('POST', INVENTORY_URL + '/inventory', local)
            if response['status'] != 200:
                raise RuntimeError(f'Inventory setup failed: {response}')
            data[mode].append({**local, 'sourceId': product['id']})
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / 'k6-input.json').write_text(json.dumps(data), encoding='utf-8')
    evidence = {'verifiedAtUtc': datetime.now(timezone.utc).isoformat(), 'k6Image': IMAGE,
                'externalCatalog': source, 'products': data, 'scenarios': {}}
    for mode in ('normal', 'contention'):
        print(f'Running k6 {mode}: ' + ('10 users for 30 seconds' if mode == 'normal' else '30 users, one order each'), flush=True)
        run = subprocess.run([
            'docker', 'run', '--rm', '--network', 'portfolio-demo_default',
            '--mount', f'type=bind,source={ROOT / "demo" / "k6"},target=/scripts,readonly',
            '--mount', f'type=bind,source={ARTIFACTS},target=/data,readonly',
            '--mount', f'type=bind,source={ARTIFACTS},target=/reports',
            '-e', f'MODE={mode}', IMAGE, 'run', '/scripts/orders.js'
        ], capture_output=True, text=True, timeout=120)
        (ARTIFACTS / f'k6-{mode}.log').write_text(run.stdout + run.stderr, encoding='utf-8')
        print(run.stdout, flush=True)
        if run.stderr:
            print(run.stderr, flush=True)
        summary_path = ARTIFACTS / f'k6-{mode}-summary.json'
        summary = json.loads(summary_path.read_text(encoding='utf-8')) if summary_path.exists() else {}
        evidence['scenarios'][mode] = {'k6ExitCode': run.returncode, 'summary': summary}

    ids = ','.join(str(p['productId']) for group in data.values() for p in group)
    stocks = {int(line.split('|')[0]): int(line.split('|')[1]) for line in database_rows(
        'inventory_db', f'SELECT product_id, quantity FROM inventory WHERE product_id IN ({ids});')}
    orders = {int(line.split('|')[0]): {'count': int(line.split('|')[1]), 'units': int(line.split('|')[2])}
              for line in database_rows('order_db', f'SELECT product_id, count(*), sum(quantity) FROM orders WHERE product_id IN ({ids}) GROUP BY product_id;')}
    rows = []
    database_passed = True
    for mode, group in data.items():
        for product in group:
            product_id = product['productId']
            saved = orders.get(product_id, {'count': 0, 'units': 0})
            stock = stocks.get(product_id)
            correct = stock is not None and stock >= 0 and stock + saved['units'] == product['quantity'] and saved['count'] == saved['units']
            if mode == 'contention':
                correct = correct and stock == 0 and saved['count'] == 5
            rows.append({'scenario': mode, 'productId': product_id, 'stock': stock, 'orders': saved, 'passed': correct})
            database_passed = database_passed and correct
    normal_order_count = sum(row['orders']['count'] for row in rows if row['scenario'] == 'normal')
    normal_created = evidence['scenarios']['normal']['summary'].get('metrics', {}).get('orders_created', {}).get('values', {}).get('count')
    # Detect stock reduced without a persisted order and successful responses without saved orders.
    database_passed = database_passed and normal_created is not None and normal_order_count == normal_created
    evidence['databaseChecks'] = rows
    evidence['databasePassed'] = database_passed
    evidence['passed'] = database_passed and all(result['k6ExitCode'] == 0 for result in evidence['scenarios'].values())
    (ARTIFACTS / 'k6-verification.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print('PostgreSQL stock/order accounting:', 'PASS' if database_passed else 'FAIL', flush=True)
    print(f'Full evidence: {ARTIFACTS / "k6-verification.json"}', flush=True)
    if not evidence['passed']:
        raise RuntimeError('k6 thresholds or database checks failed; inspect saved evidence.')


if __name__ == '__main__':
    main()
