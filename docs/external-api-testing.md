# External catalog integration and order traffic test

The demo imports sample products from the public
[DummyJSON Products API](https://dummyjson.com/docs/products), then exercises
the local Order and Inventory services. It is a test/demo integration in
`scripts/external_api_demo.py`; Spring Boot does not call DummyJSON during
normal order processing.

The script makes one external catalog GET per run, requesting only three
product IDs/titles. It validates the catalog before writing inventory, assigns
fresh local IDs, and gives each imported product five synthetic stock units.
External stock values are not imported or used for availability decisions.
PostgreSQL remains the authority for your application's stock.

For each product, ten workers submit ten one-unit order attempts. The three
products are tested in successive batches. Concurrent traffic targets only
your isolated localhost demo. It does not stress the public provider.

## Run

Requires Python 3.10+ and the existing Docker demo stack. No extra Python
packages, API keys, or hosted testing accounts are needed.

```powershell
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml up -d --wait --wait-timeout 240
python scripts/external_api_demo.py
```

On the first startup, add `--build` to the Compose command. Inventory and
Order use loopback ports 18081 and 18080. The demo project uses PostgreSQL in
memory and never mounts your laptop database volume. Leave the normal laptop
stack running; do not combine this overlay with compose.local.yaml.

The script checks service health, imports real external response data, submits
orders, and verifies both service databases with **read-only** SQL through
the fixed `portfolio-demo-postgres-1` container. Each product must have exactly
five successful orders, five 409 rejections, zero database stock, and five
persisted orders consuming five units. Any unexpected outcome fails the run.

Response bodies, timings, source-to-local ID mapping, and database results are
saved in `demo/artifacts/external-api-run.json` (ignored by Git). Cached inventory
reads are recorded separately from authoritative database checks; the script
does not assume cached reads are strongly consistent. Repeating the script
creates new local product IDs and leaves previous demo records intact.

## Verified run: 2026-10-11

| Imported product | Attempts | Successful orders | Expected 409s | Database stock |
| --- | ---: | ---: | ---: | ---: |
| Essence Mascara Lash Princess | 10 | 5 | 5 | 0 |
| Eyeshadow Palette with Mirror | 10 | 5 | 5 | 0 |
| Powder Canister | 10 | 5 | 5 | 0 |
| Total | 30 | 15 | 15 | 0 |

Unexpected responses: **0**. Each product had exactly five saved one-unit
orders. The order-traffic phase lasted **2.75 seconds**, or **10.91 order
attempts/second**. Mean response time was **744.80 ms**, nearest-rank p95
**2176.62 ms**, and maximum **2178.17 ms**. Timings include successful and
rejected order attempts; catalog retrieval and inventory setup are excluded.

These are measurements from a short local run with ten workers, not a
sustained-load benchmark or a production capacity estimate. Workers are
concurrent but not synchronized to an identical start instant. For an interview,
describe this as a bounded external-catalog integration and concurrent ordering
check. The separate Java integration tests remain the repeatable row-lock
regression tests. Reservation/compensation and idempotency are still unfinished.

A cloud load generator would require a reachable hosted demo URL. Public DNS,
TLS, and remote-load testing have not been performed.

The same catalog workflow is also tested using [k6](k6-testing.md), with normal
traffic and contention scenarios, response-time thresholds, and SQL checks.
