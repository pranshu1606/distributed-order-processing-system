"""Find the highest checkout rate the portfolio-demo stack sustains, then audit stock.

Runs constant-arrival-rate k6 stages (checkouts/s), stops at the first stage that
misses its targets, then compares consumed stock with saved orders in PostgreSQL.
Only the isolated portfolio-demo stack (tmpfs database) is touched.
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import time
from datetime import datetime, timezone

from external_api_demo import request, database_rows, INVENTORY_URL, ORDER_URL

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / 'demo' / 'artifacts'
STAGES = [25, 50, 100, 200, 400, 800, 1200, 1600]
IMAGE = 'grafana/k6:2.3.0'
PRODUCTS = 30
INITIAL_STOCK = 1_000_000


def run(command, timeout=30):
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(command, 124, '', 'timed out')


def code_version():
    head = run(['git', '-C', str(ROOT), 'rev-parse', '--short', 'HEAD']).stdout.strip()
    dirty = run(['git', '-C', str(ROOT), 'status', '--porcelain', '--', 'inventory-service', 'order-service']).stdout.strip()
    return head + ('+uncommitted-service-changes' if dirty else '')


def metric(summary, name, field):
    return (summary or {}).get('metrics', {}).get(name, {}).get('values', {}).get(field)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-rate', type=int, choices=STAGES, default=400)
    parser.add_argument('--duration', type=int, choices=(30, 60), default=30)
    parser.add_argument('--label', default='run', help='short name for this measurement, e.g. baseline')
    parser.add_argument('--rates', type=int, nargs='+', choices=STAGES,
                        help='stress mode: run exactly these stages and keep going after a failure')
    parser.add_argument('--products', type=int, choices=(1, 3, 30), default=PRODUCTS,
                        help='fewer products = hotter rows (3 or 1 models a flash sale)')
    parser.add_argument('--chaos-db-pause', type=int, choices=(3, 8), metavar='SECONDS',
                        help='freeze the demo PostgreSQL for SECONDS, 10s into each stage (fault injection)')
    parser.add_argument('--orders-only', action='store_true', help='skip the product read before each order')
    parser.add_argument('--client-retries', action='store_true',
                        help='retry 503/timeouts up to 3 attempts with the same Idempotency-Key')
    args = parser.parse_args()
    stages = args.rates or [rate for rate in STAGES if rate <= args.max_rate]
    if shutil.disk_usage(ROOT).free < 3 * 1024 ** 3:
        raise SystemExit('Free at least 3 GiB on the workspace drive first; Docker failed before at 0.3 GiB.')
    for url in (INVENTORY_URL, ORDER_URL):
        health = request('GET', url + '/actuator/health')
        if health['status'] != 200 or health['body'].get('status') != 'UP':
            raise SystemExit('Start the isolated portfolio-demo stack first (see docs/k6-testing.md).')

    base_id = time.time_ns() // 1_000_000 * 1000
    products = [{'productId': base_id + i, 'productName': f'Capacity product {i + 1}', 'quantity': INITIAL_STOCK}
                for i in range(args.products)]
    for product in products:
        created = request('POST', INVENTORY_URL + '/inventory', product)
        if created['status'] != 200:
            raise SystemExit(f'Seeding failed: {created}')
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / 'k6-capacity-input.json').write_text(json.dumps({'products': products}), encoding='utf-8')

    # Unscored warm-up: freshly started JVMs compile hot code during their first
    # requests, which would otherwise count as slow responses in the first stage.
    print('Warm-up: 10 checkouts/s for 20s (not scored) ...', flush=True)
    warm = run(['docker', 'run', '--rm', '--network', 'portfolio-demo_default', '--memory', '768m', '--cpus', '2',
                '--mount', f'type=bind,source={ROOT / "demo" / "k6"},target=/scripts,readonly',
                '--mount', f'type=bind,source={ARTIFACTS},target=/data,readonly',
                '--mount', f'type=bind,source={ARTIFACTS},target=/reports',
                '-e', 'RATE=10', '-e', 'DURATION=20s', '-e', f'RUN_ID=warmup-{base_id}',  # fresh keys every run
                IMAGE, 'run', '--quiet', '/scripts/capacity.js'], timeout=120)
    if warm.returncode not in (0, 99):  # 99 = thresholds crossed, acceptable while warming
        raise SystemExit(f'Warm-up could not run: {warm.stderr[-500:]}')
    (ARTIFACTS / 'k6-capacity-10-summary.json').unlink(missing_ok=True)
    time.sleep(5)

    report = ARTIFACTS / f'k6-capacity-{args.label}-{base_id}.json'
    evidence = {'label': args.label, 'codeVersion': code_version(),
                'startedAtUtc': datetime.now(timezone.utc).isoformat(),
                'stageSeconds': args.duration, 'products': args.products, 'initialStockEach': INITIAL_STOCK,
                'generator': {'image': IMAGE, 'memory': '768m', 'cpus': 2}, 'stages': []}
    for rate in stages:
        print(f'Stage: {rate} checkouts/s for {args.duration}s ...', flush=True)
        container = f'portfolio-k6-capacity-{rate}-{base_id}'
        summary_path = ARTIFACTS / f'k6-capacity-{rate}-summary.json'
        summary_path.unlink(missing_ok=True)
        k6_command = ['docker', 'run', '--name', container, '--network', 'portfolio-demo_default',
                  '--memory', '768m', '--memory-swap', '768m', '--cpus', '2', '--ulimit', 'nofile=65536:65536',
                  '--mount', f'type=bind,source={ROOT / "demo" / "k6"},target=/scripts,readonly',
                  '--mount', f'type=bind,source={ARTIFACTS},target=/data,readonly',
                  '--mount', f'type=bind,source={ARTIFACTS},target=/reports',
                  '-e', f'RATE={rate}', '-e', f'DURATION={args.duration}s', '-e', 'GOMEMLIMIT=600MiB',
                  '-e', f'ORDERS_ONLY={int(args.orders_only)}', '-e', f'RETRIES={int(args.client_retries)}',
                  '-e', f'RUN_ID={base_id}', IMAGE, 'run', '--quiet', '/scripts/capacity.js']
        process = subprocess.Popen(k6_command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if args.chaos_db_pause:
            # Only the disposable demo database is ever paused, and it is always unpaused.
            time.sleep(10)
            run(['docker', 'pause', 'portfolio-demo-postgres-1'])
            try:
                time.sleep(args.chaos_db_pause)
            finally:
                run(['docker', 'unpause', 'portfolio-demo-postgres-1'])
        try:
            output, _ = process.communicate(timeout=args.duration + 90)
        except subprocess.TimeoutExpired:
            process.kill()
            output, _ = process.communicate()
        k6 = subprocess.CompletedProcess(k6_command, process.returncode, output, '')
        state = run(['docker', 'inspect', container, '--format', '{{json .State}}'])
        oom = json.loads(state.stdout).get('OOMKilled') if state.returncode == 0 else None
        run(['docker', 'rm', '-f', container])
        (ARTIFACTS / f'k6-capacity-{rate}.log').write_text(k6.stdout + k6.stderr, encoding='utf-8')
        summary = json.loads(summary_path.read_text()) if summary_path.exists() else None
        stage = {
            'checkoutsPerSecond': rate, 'chaosDbPauseSeconds': args.chaos_db_pause, 'ordersOnly': args.orders_only, 'clientRetries': args.client_retries,
            'orderRetries': metric(summary, 'order_retries', 'count') or 0, 'k6ExitCode': k6.returncode, 'generatorOomKilled': oom,
            'passed': k6.returncode == 0 and summary is not None and not oom,
            'ordersCreated': metric(summary, 'orders_created', 'count') or 0,
            'ordersShed503': metric(summary, 'orders_shed', 'count') or 0,
            'ordersTimedOut': metric(summary, 'orders_timed_out', 'count') or 0,
            'unexpectedResponses': metric(summary, 'unexpected_responses', 'count') or 0,
            'droppedIterations': metric(summary, 'dropped_iterations', 'count') or 0,
            'orderSuccessRate': metric(summary, 'order_success_rate', 'rate'),
            'orderLatencyMs': (summary or {}).get('metrics', {}).get('http_req_duration{name:capacity_order}', {}).get('values'),
            'readLatencyMs': (summary or {}).get('metrics', {}).get('http_req_duration{name:capacity_read}', {}).get('values'),
            'peakGeneratorVus': metric(summary, 'vus_max', 'max'),
        }
        evidence['stages'].append(stage)
        report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        print(json.dumps({k: stage[k] for k in ('checkoutsPerSecond', 'passed', 'ordersCreated', 'ordersShed503',
                                                 'ordersTimedOut', 'unexpectedResponses', 'droppedIterations')}
                         | {'orderP95': (stage['orderLatencyMs'] or {}).get('p(95)')}), flush=True)
        if not stage['passed'] and not args.rates:
            evidence['stoppedReason'] = f'{rate} checkouts/s missed its targets; higher stages skipped.'
            break
        time.sleep(5)

    passed = [s['checkoutsPerSecond'] for s in evidence['stages'] if s['passed']]
    evidence['highestPassingRate'] = max(passed) if passed else None
    time.sleep(10)  # let requests that timed out on the client finish on the server
    ids = ','.join(str(p['productId']) for p in products)
    stock = database_rows('inventory_db', f'SELECT coalesce(sum({INITIAL_STOCK} - quantity), 0) FROM inventory WHERE product_id IN ({ids});')
    saved = database_rows('order_db', f'SELECT count(*), coalesce(sum(quantity), 0), count(DISTINCT idempotency_key) '
                                      f'FROM orders WHERE product_id IN ({ids});')
    open_reservations = database_rows('inventory_db', "SELECT count(*) FROM stock_reservations "
                                      f"WHERE status = 'RESERVED' AND product_id IN ({ids});")
    consumed = int(stock[0])
    saved_count, saved_units, distinct_keys = (int(x) for x in saved[0].split('|'))
    created = sum(s['ordersCreated'] for s in evidence['stages'])
    evidence['accounting'] = {
        'unitsConsumed': consumed, 'ordersSaved': saved_count, 'unitsInSavedOrders': saved_units,
        'leakedUnits': consumed - saved_units, 'successfulResponses': created,
        'stockMatchesOrders': consumed == saved_units,
        'distinctIdempotencyKeys': distinct_keys, 'duplicateOrders': saved_count - distinct_keys,
        # Every RESERVED reservation should belong to exactly one saved order.
        'reservationsWithoutOrder': int(open_reservations[0]) - saved_count,
    }
    evidence['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
    report.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print(json.dumps({'highestPassingRate': evidence['highestPassingRate'], **evidence['accounting']}), flush=True)
    print(f'Evidence: {report}', flush=True)


if __name__ == '__main__':
    main()
