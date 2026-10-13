# Project context

I am learning backend development from scratch. Explain what changes,
why they are needed, and how to verify them. Work in small, meaningful
steps. Inspect the actual code before making assumptions.

## Project

Distributed Order Processing System: an e-commerce backend.

Stack:
- Java 17, Maven, Spring Boot 4.0.8
- PostgreSQL 18.6
- Redis 7
- Docker and Docker Compose
- GitHub Actions CI

Services:
- order-service: port 8080, owns order_db
- inventory-service: port 8081, owns inventory_db
- Order Service calls Inventory Service through Spring RestClient.

## Implemented and manually verified

- Inventory creation, lookup, and stock reduction.
- Order creation, lookup by ID, and listing.
- Request DTOs, validation, and exception handling.
- Order controller/service/context tests; Inventory has context, concurrent
  reduction, rejection, and cache-eviction integration tests.
- Transactional inventory reduction now uses a conditional PostgreSQL
  UPDATE ... RETURNING through JdbcTemplate. This replaces the verified
  SELECT FOR UPDATE path; latest transaction/load verification is pending.
  PostgreSQL remains the authority for stock decisions.
- Redis inventory caching with a 60-second TTL.
- Cache eviction after successful stock reduction.
- JSON serialization using JacksonJsonRedisSerializer<Inventory>.
- Transaction-aware RedisCacheManager.
- RedisCacheWriter uses immediateWrites(true). Spring Data Redis 4 otherwise
  performs puts/eviction asynchronously; tests exposed delayed cache operations.
- Multi-stage Dockerfiles that build the JARs inside Docker.
- Fresh Compose deployment with automatic database initialization.
- Actuator health endpoints on both services returning UP.

## Configuration

- compose.yaml is the portable setup.
- compose.local.yaml reuses my existing laptop database volume.
- compose.local.yaml is ignored by Git.
- db/init/01-create-order-db.sql creates order_db on fresh initialization.
- .github/workflows/ci.yml runs tests with PostgreSQL and Redis,
  then builds both Docker images. Verify its actual run status.

Laptop startup:
docker compose -f compose.yaml -f compose.local.yaml up --build -d

Fresh demo startup:
docker compose up --build -d

## Current task

Compose health-check changes are implemented:
- curl is installed in both Docker runtime stages.
- Both apps probe /actuator/health every 10 seconds, with a 60-second
  startup grace period, a 5-second timeout, and 6 retries.
- Order Service depends on Inventory Service being healthy at startup.
- Portable and laptop Compose configurations pass config --quiet.
- Runtime verified on 2026-10-10: both images build, all four containers are
  healthy, and both app health endpoints return UP. Startup output confirms
  Inventory became healthy before Order started.
- Only app containers were recreated using the laptop override and --no-deps.
  PostgreSQL container ID and volume stayed unchanged; before/after row counts
  and full-table fingerprints match (2 inventory rows, 6 orders).
- Health checks were committed as 9a61323. GitHub CI for that commit completed
  successfully (checked 2026-10-11). Follow-up commit c26efb6 was pushed to main;
  its GitHub run 38157943691 completed successfully (checked 2026-10-11).
  Later local changes require a new push/run before claiming CI coverage.

Current follow-up (2026-10-11):
- Stock locking activated. Inventory's 6 tests pass against disposable
  PostgreSQL 18.6 and Redis on Java 17, including three repetitions of ten
  simultaneous reductions against five units and synchronous cache eviction.
- README startup instructions, architecture, API examples, and roadmap updated.
- CI now builds the portable stack and verifies healthy startup/endpoints.
- compose.test.yaml and compose.smoke.yaml provide isolated verification;
  PostgreSQL uses tmpfs and never mounts the laptop data volume.
- User selected "Prepare deployment files first". compose.demo.yaml, Caddyfile,
  .env.demo.example, and docs/demo-hosting.md prepare a VM demo with HTTPS and
  password access. No cloud resources created. Server/domain/public TLS pending.
- Demo Compose/Caddy configuration validated. Internal proxy checks return
  401 without credentials; authenticated health and inventory/order API checks
  pass. Test stock reduced from 10 to 8, only in the isolated smoke database.
- Final Java 17 verification: all 18 tests pass (Inventory 6, Order 12).
  Final images build and isolated startup passes. Laptop Inventory alone was
  recreated using the tested image, the local override, and --no-deps.
  All four laptop containers are healthy; both health endpoints return UP.
- PostgreSQL container ID and volume unchanged. Before/after full-table
  fingerprints match: inventory 2 rows, 350328e56012d1493c93c92c33999471;
  orders 6 rows, ee24ad4826a13dfdb80e3d424e074b02. No business writes on laptop.
- Portfolio API demo kit added: demo/portfolio.postman_collection.json,
  scripts/Invoke-ApiDemo.ps1, compose.api-demo.yaml, and docs/api-demo.md.
  Uses a separate portfolio-demo project with PostgreSQL tmpfs and loopback
  ports 18080 (Order) / 18081 (Inventory); never mounts laptop data.
  Terminal walkthrough verifies 10 steps. Newman 6 verifies all 10 Postman
  requests / 20 assertions, including stock 10 -> 8, 409/400 rejections,
  and unchanged stock after rejection (verified 2026-10-11).
  Demo stack is intentionally left running for the user to record/use.
  Generated response evidence under demo/artifacts/ is ignored by Git.
- External API integration demo added: scripts/external_api_demo.py imports
  three product IDs/titles from DummyJSON with one GET per run, validates the
  catalog, assigns fresh local IDs and five synthetic units per product.
  Concurrent order traffic stays on the portfolio-demo stack; Spring Boot's
  normal runtime does not depend on DummyJSON.
  Verified 2026-10-11: 30 order attempts, 10 workers, 15 successes, 15 expected
  409s, zero unexpected responses. Read-only SQL confirmed stock zero and five
  saved orders per imported product. Order phase 2.75s, 10.91 attempts/s,
  mean 744.80ms, p95 2176.62ms. Short local check, not a capacity benchmark.
  Evidence: demo/artifacts/external-api-run.json (ignored); instructions and
  measured results: docs/external-api-testing.md. No laptop business writes.
- k6 2.3.0 traffic tests added and verified 2026-10-11: demo/k6/orders.js and
  scripts/run_k6.py. One external catalog GET, all traffic on portfolio-demo.
  Normal: 10 VUs for 30s, 1,214 HTTP requests, 607 saved orders, zero unexpected
  failures; order p95 533.15ms, read p95 139.33ms, about 20.12 orders/s.
  Contention: 30 VUs / 30 attempts, 15 successes and 15 expected 409s, p95
  336.66ms. All thresholds and database accounting checks pass. Each contention
  product ends at zero stock with five persisted orders; normal stock consumed
  equals saved units. Short local tests, not production capacity evidence.
  Docs: docs/k6-testing.md; generated evidence: demo/artifacts/k6-verification.json.
- User requested about 12,000 users. Added high-users.js/run_k6_high_users.py.
  Stress attempt 2026-10-11: 100 VUs, 562 requests, 7.47% HTTP failures,
  239 successful order responses, order p95 5001ms; 1,000 VUs, 2,808 requests,
  65.14% failures, zero successful order responses, order p95 5002ms.
  Both failed thresholds. Docker engine connection interrupted during 3,000
  VUs; no complete summary. 6,000-VU generator OOMKilled=true. 12,000 attempt
  stopped manually during recovery, no summary. Do not claim 12,000 supported.
  Engine memory about 3.7 GiB; generator capped at 768 MiB/two CPUs.
  All eight laptop/demo containers exited during interruption and were restored
  healthy; all four API health endpoints UP. Laptop PostgreSQL container ID,
  persistent volume, row counts and full-table fingerprints unchanged (2/6).
  Demo tmpfs lost runtime rows on restart; stress accounting is unavailable.
  Runner now stops escalation on the first failed/incomplete stage; syntax
  checked without another heavy load run. Docs/results: docs/k6-testing.md
  and ignored demo/artifacts/k6-high-users-verification.json.

## Remaining work

- Active 1,000-user follow-up (2026-10-11/12): removed cached Order precheck,
  disabled SQL printing/Open-in-View, added bounded Apache HTTP pool/no retries
  and isolated compose.performance.yaml. These first changes passed 18 Java
  tests and a warm ten-step API walkthrough, but 100-VU/30s load still failed:
  1,248 HTTP requests, 38.54% failures, 239 successful order responses,
  order p95 5186ms. SQL found 624 units consumed and 529 orders saved; the
  existing partial-failure gap was observed under dependency read timeouts.
  Evidence: demo/artifacts/k6-1000-users-verification-1791742979259000.json.
  Follow-up conditional JDBC UPDATE and rollback test were added, and G1GC
  selected for demo JVMs. Images built, but runtime recreation and tests were
  interrupted by Docker/WSL storage errors. Docker logs at 18:29:57 UTC show
  disk I/O failures, an aborted EXT4 journal/read-only remount, and daemon crash.
  C: had 0.32 GiB free; Windows RAM about 430 MiB free out of 8 GiB.
  Docker restart failed, so latest code is unverified and services cannot be
  confirmed running. User selected "I'll free disk space now". Await space,
  restore engine and laptop DB fingerprints, then run 19 tests and k6 up to
  1,000. Do not claim this target passed. Earlier 2/6 DB fingerprints were
  verified before this latest engine failure; recheck after recovery.
  Maven build/test memory bounds and shared dependency caches added to reduce
  repeated downloads. Test/build configuration changes need verification too.
  Graceful Docker stop timed out too; only verified Docker Desktop processes
  were stopped to halt retry loops. Last disk-space check: 0.81 GiB free.
  No Docker volumes were deleted or reset. Engine and services are currently
  stopped pending user disk cleanup; restore and reverify before heavy work.
  Compose parsing, Python compilation, and k6 JS syntax checks pass.

- Launch a public demo when a server/domain and budget are supplied; files and
  hosting documentation are prepared. Verify real DNS, TLS, and APIs afterward.
- Portfolio site (pranshu1606.github.io, separate repo) updated 2026-10-12 in
  commit 0cedd66: project card details plus a "Testing report" panel (#dops-tests)
  using only the verified numbers above, including failed 100/1,000-VU stress
  runs. Live deploy confirmed. Update the site if later results change.
- API demo kit, k6 scripts and test docs committed as 568f686 (demo/test files
  only; README, compose.test.yaml and the conditional-UPDATE/HTTP-pool code stay
  uncommitted until verified). GitHub CI run 38167103509 succeeded (2026-10-12).
- Kubernetes and Azure are later roadmap items.
- Payment, notifications, and messaging are optional extensions.
- Stress/resilience milestone (2026-10-13), uncommitted: Docker restored (laptop
  DB fingerprints 2/6 unchanged after crash recovery). Added stock reservations
  (stock_reservations table, reservationId on reduce, idempotent release with
  tombstones), background compensation in Order (InventoryClient), 503 +
  Retry-After, 1s/1s/3s Order HTTP budget, Hikari connection-timeout 2s, and
  Idempotency-Key (unique orders.idempotency_key, 422 on reuse). 35 tests pass
  (Inventory 16, Order 19). New arrival-rate test demo/k6/capacity.js +
  scripts/run_k6_capacity.py (warm-up, stages, --products, --rates,
  --chaos-db-pause, --client-retries, DB audit). Chaos (DB frozen 8s, 100/s):
  before fix 135 units lost; after fix 0 lost, 0 duplicates, 0 orphan
  reservations. Capacity before fix: 400/s passed (800/s borderline); after fix
  50-200/s highest passing under host memory pressure (Windows ~600 MiB free;
  untouched reads also slowed 5x), so re-measure on a quiet machine before
  quoting throughput. Details: docs/stress-and-resilience.md.
  Final suite (2026-10-13, ~940 MiB free): capacity 400/s passed (p95 9 ms),
  flash-sale 3 products 400/s passed, overload 1600/s ~700 orders/s p95 1.96 s
  0 errors, chaos with retries 2,784/2,784 checkouts ordered (362 retries).
  Every audit: 0 lost, 0 duplicates, 0 orphan reservations.
- Remaining gap: release retries are in memory; a crash can leave a RESERVED
  reservation without an order (detectable by audit, not auto-repaired).

## Working rules

Documentation milestone (2026-10-12): `docs/testing-and-validation.md` records
architecture, Java coverage (18 historically passed / 19 currently expected),
API/demo/integration checks, baseline and failed k6 results, observed accounting
gap, evidence locations, recovery/retest steps, and defensible portfolio wording.
README links it; documentation does not imply runtime recovery or 1,000-user
verification. Latest build/test/load checks remain pending.

- Preserve existing database data.
- Do not delete volumes or reset databases without explicit permission.
- Keep PostgreSQL as the authority for stock decisions.
- Do not claim cached inventory reads are strongly consistent.
- Run relevant checks and distinguish verified results from assumptions.
- Keep this context updated as milestones are completed.
