"""Step toward 12,000 k6 VUs; stop escalation when a stage fails."""
import json
import argparse
import shutil
import pathlib
import subprocess
import time
from datetime import datetime, timezone

from external_api_demo import request, database_rows, SOURCE_URL, INVENTORY_URL, ORDER_URL

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / 'demo' / 'artifacts'
STAGES = [100, 1000, 3000, 6000, 12000]
IMAGE = 'grafana/k6:2.3.0'


def docker_result(command, timeout=20):
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(command, 124, '', 'Docker command timed out; engine may be unavailable.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-users', type=int, choices=(100, 1000, 12000), default=1000)
    parser.add_argument('--duration', type=int, choices=(10, 30, 60), default=30)
    args = parser.parse_args()
    if shutil.disk_usage(ROOT).free < 2 * 1024 ** 3:
        raise RuntimeError('Free at least 2 GiB on the workspace drive before load testing; Docker previously failed with disk I/O errors.')
    for url in (INVENTORY_URL, ORDER_URL):
        health = request('GET', url + '/actuator/health')
        if health['status'] != 200 or health['body'].get('status') != 'UP':
            raise RuntimeError('The isolated portfolio-demo stack must be healthy before testing.')
    source = request('GET', SOURCE_URL)
    if source['status'] != 200 or not isinstance(source['body'], dict):
        raise RuntimeError('Catalog unavailable.')
    catalog = source['body'].get('products')
    if not isinstance(catalog, list) or len(catalog) != 3 or any(
        not isinstance(p, dict) or not isinstance(p.get('title'), str) or not p['title'].strip() for p in catalog
    ):
        raise RuntimeError('Invalid external catalog.')
    base_id = time.time_ns() // 1_000_000 * 1000
    products = []
    for index, product in enumerate(catalog):
        local = {'productId': base_id + index, 'productName': product['title'], 'quantity': 1000000}
        result = request('POST', INVENTORY_URL + '/inventory', local)
        if result['status'] != 200:
            raise RuntimeError(f'Seeding failed: {result}')
        products.append(local)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / 'k6-high-users-input.json').write_text(json.dumps({'products': products}), encoding='utf-8')
    evidence = {'startedAtUtc': datetime.now(timezone.utc).isoformat(), 'targetUsers': args.max_users,
                'durationSecondsPerStage': args.duration,
                'generatorMemoryLimit': '768m', 'generatorCpuLimit': 2, 'products': products, 'stages': []}
    report = ARTIFACTS / f'k6-{args.max_users}-users-verification-{base_id}.json'
    report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    for users in STAGES:
        if users > args.max_users:
            break
        print(f'Attempting {users:,} users for {args.duration}s (k6 memory capped at 768 MiB)...', flush=True)
        # Keep a named container until its exit/OOM state has been recorded.
        container = f'portfolio-k6-users-{users}-{base_id}'
        command = [
            'docker', 'run', '--name', container, '--network', 'portfolio-demo_default',
            '--memory', '768m', '--memory-swap', '768m', '--cpus', '2',
            '--ulimit', 'nofile=65536:65536',
            '--mount', f'type=bind,source={ROOT / "demo" / "k6"},target=/scripts,readonly',
            '--mount', f'type=bind,source={ARTIFACTS},target=/data,readonly',
            '--mount', f'type=bind,source={ARTIFACTS},target=/reports',
            '-e', f'USERS={users}', '-e', f'DURATION={args.duration}s', '-e', 'GOMEMLIMIT=600MiB', IMAGE,
            'run', '--quiet', '/scripts/high-users.js',
        ]
        timed_out = False
        summary_path = ARTIFACTS / f'k6-users-{users}-summary.json'
        # Remove only this generated summary so an earlier run cannot supply evidence.
        summary_path.unlink(missing_ok=True)
        try:
            run = subprocess.run(command, capture_output=True, text=True, timeout=150)
            output, exit_code = run.stdout + run.stderr, run.returncode
        except subprocess.TimeoutExpired:
            timed_out = True
            docker_result(['docker', 'stop', '--timeout', '5', container])
            logs = docker_result(['docker', 'logs', container])
            output, exit_code = logs.stdout + logs.stderr, None
        (ARTIFACTS / f'k6-users-{users}.log').write_text(output, encoding='utf-8')
        state = docker_result(['docker', 'inspect', container, '--format', '{{json .State}}'])
        container_state = json.loads(state.stdout) if state.returncode == 0 else {'inspectionError': state.stderr}
        docker_result(['docker', 'rm', container])
        summary = json.loads(summary_path.read_text()) if summary_path.exists() else None
        stage = {'configuredUsers': users, 'exitCode': exit_code, 'timedOut': timed_out,
                 'containerState': container_state, 'summary': summary}
        stage['actualPeakUsers'] = (summary or {}).get('metrics', {}).get('vus', {}).get('values', {}).get('max')
        evidence['stages'].append(stage)
        report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        metrics = (summary or {}).get('metrics', {})
        print(json.dumps({
            'configuredUsers': users, 'exitCode': exit_code, 'oomKilled': container_state.get('OOMKilled'),
            'timedOut': timed_out, 'requests': metrics.get('http_reqs', {}).get('values'),
            'ordersCreated': metrics.get('orders_created', {}).get('values'),
            'httpFailures': metrics.get('http_req_failed', {}).get('values'),
            'orderLatency': metrics.get('http_req_duration{name:high_users_order}', {}).get('values'),
        }), flush=True)
        if exit_code != 0 or timed_out or summary is None or container_state.get('OOMKilled') or state.returncode != 0 or stage['actualPeakUsers'] != users:
            evidence['stoppedReason'] = f'{users}-user stage failed or produced incomplete evidence; larger stages skipped.'
            report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
            print(evidence['stoppedReason'], flush=True)
            break
        for url in (INVENTORY_URL, ORDER_URL):
            health = request('GET', url + '/actuator/health')
            if health['status'] != 200 or health['body'].get('status') != 'UP':
                raise RuntimeError('Backend unhealthy after stage; escalation stopped.')
        # Pause briefly between stages; timed-out requests may still finish on the backend.
        time.sleep(10)

    ids = ','.join(str(p['productId']) for p in products)
    try:
        stocks = database_rows('inventory_db', f'SELECT product_id, quantity FROM inventory WHERE product_id IN ({ids}) ORDER BY product_id;')
        orders = database_rows('order_db', f'SELECT product_id, count(*), sum(quantity) FROM orders WHERE product_id IN ({ids}) GROUP BY product_id ORDER BY product_id;')
    except (subprocess.SubprocessError, OSError) as error:
        evidence['stockAccountingPassed'] = None
        evidence['databaseVerificationError'] = str(error)
        report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        raise SystemExit(f'Database verification unavailable; evidence saved to {report}')
    evidence['inventoryRows'] = stocks
    evidence['orderRows'] = orders
    stock_map = {int(row.split('|')[0]): int(row.split('|')[1]) for row in stocks}
    order_map = {int(row.split('|')[0]): int(row.split('|')[2]) for row in orders}
    evidence['stockAccountingPassed'] = all(stock_map.get(p['productId'], -1) >= 0 and
        stock_map.get(p['productId'], -1) + order_map.get(p['productId'], 0) == p['quantity'] for p in products)
    evidence['successfulOrderResponses'] = sum(
        (stage['summary'] or {}).get('metrics', {}).get('orders_created', {}).get('values', {}).get('count', 0)
        for stage in evidence['stages'])
    evidence['savedOrderCount'] = sum(int(row.split('|')[1]) for row in orders)
    evidence['successfulResponsesMatchSavedOrders'] = evidence['savedOrderCount'] == evidence['successfulOrderResponses']
    evidence['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
    report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print(f'Stages recorded: {len(evidence["stages"])}. Stock accounting: {evidence["stockAccountingPassed"]}. Evidence: {report}', flush=True)
    if evidence.get('stoppedReason') or not evidence['stockAccountingPassed'] or not evidence['successfulResponsesMatchSavedOrders']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
