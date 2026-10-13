# Stress, capacity and resilience testing

Recorded 2026-10-13 on one laptop (8 GB RAM, Docker Desktop VM with 3.9 GiB and
12 CPUs). Everything ran on the isolated `portfolio-demo` stack, whose PostgreSQL
lives in tmpfs; laptop data was never mounted. These are short local runs, not
production capacity claims.

## Why the earlier "1,000 / 12,000 users" runs failed

The older test (`demo/k6/high-users.js`) gave every virtual user a loop with a
0.2 s pause, so "1,000 users" meant roughly 5,000 requests per second from one
laptop that was also running both services, PostgreSQL, Redis and k6. Real
shoppers pause between actions. The capacity test therefore sets an **arrival
rate** (checkouts per second) and finds the highest rate that meets targets.
One checkout = a cached stock read + an order.

To relate rates to users, pick an assumption and say it out loud. For example,
if each active shopper completes one checkout per minute, 200 checkouts/s is
about 12,000 concurrent shoppers. That is an illustration, not a measurement of
12,000 connections.

## How to run

```powershell
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml -f compose.performance.yaml up --build -d --wait
cd scripts
python run_k6_capacity.py --label capacity --max-rate 800             # stops at the first failing stage
python run_k6_capacity.py --label hot --products 3 --max-rate 800     # flash sale: 3 hot rows
python run_k6_capacity.py --label overload --rates 1600               # stress past capacity
python run_k6_capacity.py --label chaos --rates 100 --orders-only --chaos-db-pause 8 --client-retries
```

Each stage passes only if order p95 < 1 s, read p95 < 500 ms, at least 99% of
orders succeed, there are no unexpected responses, and k6 never runs out of
workers. A 20 s unscored warm-up runs first so JVM start-up compilation is not
counted. After the stages, the runner audits PostgreSQL: stock consumed must
equal units in saved orders, every key must map to one order, and every
`RESERVED` reservation must belong to a saved order. Evidence goes to
`demo/artifacts/k6-capacity-<label>-<id>.json` (ignored by Git).

`--chaos-db-pause` freezes only `portfolio-demo-postgres-1` (`docker pause`) 10 s
into the stage and always unpauses it.

## Results before the reservation fix (commit 568f686 + conditional UPDATE)

| Scenario | Result |
| --- | --- |
| Capacity, 30 products | Passed every stage to 400 checkouts/s (order p95 14 ms). 800/s passed once standalone (36,001 orders in 45 s, p95 105 ms) and failed once at the end of a long run (p95 827 ms, 1,479 dropped). |
| Flash sale, 3 products | Passed to 400/s; 800/s failed on latency. No lost stock. |
| Overload, 1,600/s | ~1,110 checkouts/s processed, p95 1.03 s, **0 errors, 0 lost units**; k6 hit its 1,200-worker cap. |
| Chaos: DB frozen 8 s at 100 orders/s | 269 client timeouts, 127 HTTP 500s, **135 units of stock lost** (consumed 3,150, saved 3,015). |

At 800/s the busiest container was inventory-service (~2.8 cores), then
order-service (~1.7), PostgreSQL (~1.3) and k6 (~1.1 of its 2).

## What changed

1. **Stock reservations (Inventory).** Each order sends a `reservationId`. The
   reservation row and the stock reduction commit together, so a retry with the
   same ID never reduces twice. `POST /inventory/reservations/{id}/release`
   returns stock exactly once. If release arrives first it leaves a tombstone, so a
   late reduction is refused.
2. **Compensation (Order).** If Inventory times out, fails or returns 5xx, or the
   order cannot be saved, Order releases the reservation in the background with
   retries (1, 2, 4, 8, 16 s). Release is idempotent, so retrying is safe.
3. **Fail fast.** Order answers `503` with `Retry-After: 1` instead of a generic
   500. Its time budget is now 1 s connect + 1 s pool wait + 3 s read (was 2 + 2 + 4),
   so it replies before a typical 5 s client timeout. Both services wait at most
   2 s for a database connection (Hikari default was 30 s).
4. **Idempotency-Key (Order).** Clients may send `Idempotency-Key` (1-100 chars).
   A retry returns the original order; a concurrent duplicate loses on a unique
   constraint, releases its own stock and returns the winner's order; reusing a
   key for a different order returns `422`.

Tests: 35 pass (Inventory 16, Order 19), including a five-times repeated race of
reduce and release with the same reservation ID.

## Results after the fix (final run, 2026-10-13)

Run back to back on the fixed code with about 940 MiB of Windows RAM free. Each
stage lasted 30 s. "Dropped" means k6 had no free worker to start a checkout,
which only happens once the system is saturated.

| Scenario | Highest passing stage | Details |
| --- | --- | --- |
| Capacity, 30 products | **400 checkouts/s** (800 HTTP requests/s) | Order median 5 ms, p95 9 ms, p99 14 ms. 800/s failed: 21,839 orders, p95 787 ms, 2,162 dropped. |
| Flash sale, 3 products | **400 checkouts/s** | Order p95 13 ms. 800/s failed: 19,319 orders, p95 1.71 s, 4,682 dropped. |
| Overload, 1,600/s | none (by design) | 21,135 orders (~700/s), p95 1.96 s, **0 errors**. |
| Chaos: DB frozen 8 s at 100 orders/s, clients retry with the same key | none (latency target missed during the freeze, p95 3.84 s) | **All 2,784 checkouts ended in an order** after 362 retries. 217 checkouts not started (k6 out of workers). |

Every run passed the database audit: stock consumed equals units in saved
orders, **0 lost units, 0 duplicate orders, 0 reservations without an order**.
Capacity matches the pre-fix runs (400/s), so the reservation write and key
lookup cost nothing measurable at these rates.

Earlier same-day runs on the fixed code peaked at 50-200/s while Windows had
only 576-667 MiB free; product reads, which this change did not touch, slowed
5x at the same time. Treat laptop throughput as indicative, and use a quiet
machine (ideally k6 on a separate one) for anything you quote.

## Known limits

- Background release retries live in memory. If Order stops before a release
  succeeds, the reservation stays `RESERVED` with no order; the audit query finds
  it, but nothing repairs it automatically yet (next step: a reconciliation job
  or a transactional outbox).
- If the order database itself stalls, a client can time out while its order is
  saved later. Retrying with the same `Idempotency-Key` returns that order.
- One machine runs the load generator and the system under test. Horizontal
  scaling (several service replicas behind a load balancer) is not measured.
