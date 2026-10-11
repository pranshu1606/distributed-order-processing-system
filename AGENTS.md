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
- Transactional inventory reduction uses findByProductIdForUpdate with
  PESSIMISTIC_WRITE; PostgreSQL remains the authority for stock decisions.
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
  successfully (checked 2026-10-11). New local changes are not yet covered by CI.

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

## Remaining work

- Launch a public demo when a server/domain and budget are supplied; files and
  hosting documentation are prepared. Verify real DNS, TLS, and APIs afterward.
- Push current changes and verify the new GitHub CI run.
- Kubernetes and Azure are later roadmap items.
- Payment, notifications, and messaging are optional extensions.
- Reliability gap: inventory reduction can succeed before saving the
  order fails. Reservation/compensation and idempotency remain unfinished.

## Working rules

- Preserve existing database data.
- Do not delete volumes or reset databases without explicit permission.
- Keep PostgreSQL as the authority for stock decisions.
- Do not claim cached inventory reads are strongly consistent.
- Run relevant checks and distinguish verified results from assumptions.
- Keep this context updated as milestones are completed.
