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
- Order controller and service tests; Inventory has a context-startup test only.
- Transactional inventory reduction; PESSIMISTIC_WRITE query is defined but
  reduceInventory still calls the unlocked findByProductId query.
- Redis inventory caching with a 60-second TTL.
- Cache eviction after successful stock reduction.
- JSON serialization using JacksonJsonRedisSerializer<Inventory>.
- Transaction-aware RedisCacheManager.
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
- GitHub CI for commit 8676cf3 completed successfully (checked 2026-10-10);
  it does not cover the current uncommitted health-check changes.

## Remaining work

- Affordable public demo hosting and documentation.
- Activate and verify the existing locked stock-reduction query; concurrency
  protection is not currently implemented despite the earlier handoff claim.
- README roadmap and manual startup instructions predate Docker/caching/CI work.
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
