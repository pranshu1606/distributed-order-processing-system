# k6 traffic tests

Verified on 2026-10-11 using **Grafana k6 2.3.0**, against the separate
portfolio-demo stack. The runner fetches one three-product sample from DummyJSON,
then creates separate local stock for normal traffic and contention. Only the
local demo receives concurrent requests; the public catalog receives one GET.

## Results

| Measurement | Normal traffic | Stock contention |
| --- | ---: | ---: |
| Virtual users | 10 | 30 |
| Workload | 30 seconds, repeated inventory read + order | One order per user |
| HTTP requests | 1,214 | 30 |
| Orders saved | 607 | 15 |
| Expected stock rejections (409) | 0 | 15 |
| Unexpected HTTP failures | 0 | 0 |
| Checks passed | 1,214 / 1,214 | 30 / 30 |
| Order p95 response time | 533.15 ms | 336.66 ms |
| Order average response time | 202.44 ms | 276.59 ms |

Normal traffic completed in 30.17 seconds including final in-flight iterations:
**40.24 HTTP requests/second**, or **20.12 orders/second**. Inventory reads had
p95 **139.33 ms**. The slowest normal order took **2.81 seconds**; p95 is not a
guarantee that every request finishes within that time.

All k6 thresholds passed. Normal order p95 was required to stay below 2 seconds,
inventory-read p95 below 1 second, checks at 100%, and unexpected failures at
zero. These are demo test goals, not established production service objectives.
In the contention scenario, 409 is explicitly accepted as a correct business
outcome and tracked separately from unexpected errors. It requires exactly
15 successful orders and 15 stock rejections.

## Attempt to reach 12,000 users (2026-10-11)

Added `demo/k6/high-users.js` and `scripts/run_k6_high_users.py` for stages of
100, 1,000, 3,000, 6,000, and 12,000 concurrent virtual users. Each stage is
configured for 10 seconds plus 10 seconds to finish in-flight iterations, using
three stocked products, a 200 ms pause, and five-second HTTP timeouts.

| Configured users | HTTP requests | HTTP failure rate | Order p95 | Result |
| ---: | ---: | ---: | ---: | --- |
| 100 | 562 | 7.47% | 5,001 ms | Failed thresholds; 239 successful order responses |
| 1,000 | 2,808 | 65.14% | 5,002 ms | Failed thresholds; zero successful order responses |
| 3,000 | Unavailable | Unavailable | Unavailable | Docker engine connection interrupted; no complete summary |
| 6,000 | Unavailable | Unavailable | Unavailable | Generator OOM-killed; Docker recorded OOMKilled=true |
| 12,000 | Unavailable | Unavailable | Unavailable | Attempt stopped; no complete summary |

**12,000 concurrent users were not successfully demonstrated.** The Docker
engine had about 3.7 GiB of memory shared by the services and load generator.
The generator was capped at 768 MiB and two CPUs. Its resource limit, shared
host, and contention on three product rows constrain these measurements;
they do not establish production capacity. The engine interruption's cause
was not conclusively diagnosed. A timeout does not prove an order was not saved.

All eight laptop/demo containers exited during the engine interruption. They
were restarted using their existing configurations. The laptop PostgreSQL
container ID, volume, inventory fingerprint (2 rows), and order fingerprint
(6 rows) matched the baseline. The disposable demo database uses tmpfs and
lost its runtime data on restart, so final stock accounting for this stress run
could not be verified. Earlier test summaries remain historical evidence.

The runner now stops escalation on the first failed stage, missing summary,
or generator failure. This change was checked without repeating the heavy
load. It no longer continues into larger stages after a failed test.
Generated results are in `demo/artifacts/k6-high-users-verification.json`.
For a future run on a suitably provisioned test environment:

```powershell
python scripts/run_k6_high_users.py --max-users 12000 --duration 10
```

The script's nonzero exit means a stage failed or stock accounting failed.
Larger stages are skipped when an earlier stage fails.

## Follow-up toward 1,000 users

The verified portfolio wording for the earlier version is:

> Built a distributed e-commerce backend with Spring Boot, PostgreSQL, Redis,
> Docker, and CI. In a local k6 test with 10 concurrent virtual users, processed
> 607 orders in about 30 seconds with zero unexpected HTTP failures and a
> 533 ms order p95. Verified stock accounting and concurrent stock rejections.

This is a measured local result for that version, not a production capacity
guarantee or a claim of 1,000 requests per second.

The first tuning pass removed Order's cached inventory precheck, disabled SQL
printing and Open-in-View, and bounded outbound HTTP connections. All 18 Java
tests and a warm API walkthrough passed. A 100-VU/30s rerun still failed:
1,248 HTTP requests, 38.54% HTTP failures, order p95 5,186 ms, and 239 successful
order responses. Database queries found 624 stock units consumed and 529 saved
orders. Inventory HTTP read timeouts exposed the existing partial-failure gap.
Evidence: `demo/artifacts/k6-1000-users-verification-1791742979259000.json`.

Stock reduction now uses a conditional PostgreSQL `UPDATE ... RETURNING`
instead of a separate locking SELECT and entity update. PostgreSQL rechecks
the update predicate after competing transactions, as described in its
[Read Committed documentation](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED).
A rollback/cache integration test was added, and the demo selects G1 GC.
Intermediate conditional-update/G1 images built; subsequent HTTP timeout and
build/cache configuration edits still need a fresh build. Final verification
was interrupted by a Docker/WSL storage failure. C: had 0.32 GiB free, and Docker logs recorded disk I/O errors,
an aborted EXT4 journal, and a read-only filesystem. The engine restart failed.
The user is freeing disk space; **1,000-user support is not verified yet**.

The [project testing and validation report](testing-and-validation.md) combines
the Java coverage, API demo, load-test failures, preservation checks, and recovery
procedure, with the current verification boundary for each change.

After storage/engine recovery, run regression tests before the tuned benchmark:

```powershell
docker compose -p order-verification -f compose.test.yaml run --rm tests
docker compose -p order-verification -f compose.test.yaml stop postgres redis
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml -f compose.performance.yaml up --build -d --wait --wait-timeout 240
python scripts/run_k6.py
python scripts/run_k6_high_users.py --max-users 1000 --duration 30
```

The runner defaults to a maximum of 1,000 VUs and 30 seconds per stage. It uses
the same three-product read/order workload, five-second request timeouts, and
strict latency/error thresholds, and saves an immutable verification report
named `k6-1000-users-verification-{runId}.json`. It checks both stock accounting
and successful-response counts against persisted orders, and verifies that
the configured concurrent-user count was actually reached. It requires at
least 2 GiB free on the workspace drive. The 10/30-user tests above validate
the changed ordering path and warm the services before higher traffic.
Stop on a failed stage; do not raise the timeout or relax the thresholds to turn a failure into
a capacity claim. Reservations/idempotency still require separate work.

## Database verification for the earlier 10/30-user tests

After k6 finishes, read-only PostgreSQL queries check the imported product IDs:

| Scenario / product | Initial stock | Saved one-unit orders | Final stock |
| --- | ---: | ---: | ---: |
| Normal / product 1 | 10,000 | 202 | 9,798 |
| Normal / product 2 | 10,000 | 202 | 9,798 |
| Normal / product 3 | 10,000 | 203 | 9,797 |
| Contention / product 1 | 5 | 5 | 0 |
| Contention / product 2 | 5 | 5 | 0 |
| Contention / product 3 | 5 | 5 | 0 |

Stock consumed equals persisted order quantity for every product. The normal
database order count also matches k6's successful-response count. This run
found no overselling or missing saved orders; it does not exercise the known
partial-failure gap between inventory reduction and order persistence.

## Rerun

Requires Python 3.10+, Docker, and Compose 2.24.4+. No extra Python packages or
k6 account are needed; k6 runs in Docker.

```powershell
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml up -d --wait --wait-timeout 240
docker pull grafana/k6:2.3.0
python scripts/run_k6.py
```

Add `--build` to the Compose command on a fresh setup. The runner uses the fixed
demo container/network and never mounts the laptop database volume. It creates
new product IDs on each run and leaves previous demo data intact. To stop only
the demo afterward:

```powershell
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml down
```

Files:

- `demo/k6/orders.js`: scenarios, response checks, counters, and thresholds.
- `scripts/run_k6.py`: catalog fetch, seed data, Docker execution, SQL checks.
- `demo/artifacts/k6-normal-summary.json` and `k6-contention-summary.json`: metrics.
- `demo/artifacts/k6-verification.json`: full metrics, product mapping, and SQL evidence.
- `demo/artifacts/k6-normal.log` and `k6-contention.log`: k6 output.

Generated evidence is ignored by Git. The script exits with an error if any
k6 threshold or database check fails.

## Scope and interpretation

The normal scenario uses k6's
[constant virtual users](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-vus/)
with a 200 ms pause after each read/order pair. Requests slow down when the
server slows down; this is not a fixed arrival-rate test. The contention scenario
uses 30 virtual users with one iteration each. These tests run on the same laptop
as the services, with a warm running stack, and do not establish production
capacity or measure internet latency. k6's
[thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/)
determine pass/fail; [custom summaries](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)
save the measured evidence.

Public hosting, external load generators, long soak tests, and injected
dependency failures remain separate work. Cached reads are not strongly
consistent; PostgreSQL is used for final stock verification.
