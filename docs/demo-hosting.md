# Affordable demo deployment

Use a small Linux VM with Docker Compose for this learning demo. Start around
2 vCPUs / 4 GB RAM (an estimate, to verify by measuring memory). Build the apps
sequentially to limit peak memory. Kubernetes is a later learning milestone.

Checked 2026-10-11: Hetzner's Germany/Finland CX23 list price is EUR 5.49/month,
excluding IPv4 and VAT, per its
[official price adjustment](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/).
Check [plans and availability](https://www.hetzner.com/cloud/cost-optimized/)
before ordering. Compare a 4 GB plan on
[DigitalOcean's pricing page](https://www.digitalocean.com/pricing/droplets)
as an alternative. Domain, tax, backups, and traffic may add cost. No paid
resource has been provisioned.

## Setup on a fresh Linux server

1. Choose a server within your budget, add your SSH public key, and install
   Docker/Compose using the
   [official Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/).
   Allow TCP 80/443; restrict SSH to your IP when possible. Leave app,
   PostgreSQL, and Redis ports closed.
2. Point your domain's DNS A record at the server. Add an AAAA record only
   if IPv6 is configured and reachable.
3. Clone the repository and prepare secrets:

   ```sh
   git clone https://github.com/pranshu1606/distributed-order-processing-system.git
   cd distributed-order-processing-system
   cp .env.demo.example .env.demo
   chmod 600 .env.demo
   docker run --rm -it caddy:2 caddy hash-password
   ```

   Enter the demo password at the prompt. Edit `.env.demo` with the domain,
   a long random database password, username, and generated password hash.
   Keep the hash in single quotes to preserve dollar signs. This file is
   ignored by Git. On an existing volume, changing POSTGRES_PASSWORD in the
   file does not change the database password; initialization runs only once.
4. Ensure the checkout contains the latest locking fix (unpublished local
   changes will not appear in a fresh clone), then validate/build/start:

   ```sh
   docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml config --quiet
   docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml build inventory-service
   docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml build order-service
   docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml up -d --wait --wait-timeout 240
   docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml ps
   ```

   This is a standalone Compose file. Do not combine it with compose.local.yaml.
   Its project volume is separate from laptop data. Only Caddy publishes ports;
   it provides [automatic HTTPS](https://caddyserver.com/docs/automatic-https)
   and [basic authentication](https://caddyserver.com/docs/caddyfile/directives/basic_auth).
   App containers have internal health checks. Caddy's running status alone
   does not prove DNS/certificate setup succeeded.
5. Verify from another machine (replace domain/username):

   ```sh
   curl --fail --user demo https://demo.example.com/health/inventory
   curl --fail --user demo https://demo.example.com/health/order
   curl --fail --user demo https://demo.example.com/orders
   ```

   Curl prompts for the demo password. Both health routes should return UP.
   Unauthenticated requests should return 401. Run README API examples through
   this HTTPS domain with authentication; /inventory and /orders both work there.
   Use synthetic data and share credentials only with intended reviewers.

## Preserve data and operate

Use the same --env-file, -p, and -f options for every command. stop and down
without -v preserve PostgreSQL's named volume. Back up both databases before
upgrades (Linux shell commands):

```sh
mkdir -p backups
docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml exec -T postgres pg_dump -U postgres inventory_db > backups/inventory.sql
docker compose --env-file .env.demo -p order-demo -f compose.demo.yaml exec -T postgres pg_dump -U postgres order_db > backups/orders.sql
```

Copy backups off the server and test restores into a separate disposable
database. These dumps are not an atomic cross-service snapshot; stop incoming
writes if coordinated backup is needed. Pull a chosen commit, rebuild apps
sequentially, then run up -d --no-deps --wait inventory-service order-service
with the same options. Verify health and logs afterward.

This single-server demo can have downtime. Restart policies recover exited
processes but do not fix unhealthy apps. The stock-reduction/order-save gap,
idempotency, schema migrations, and high availability remain unfinished.
Configure budget alerts and backups during actual server setup.

## Status

Deployment files are prepared. Live hosting, DNS, TLS, authenticated API checks,
and CI for the deployed commit still require verification. Add the public URL
and deployment date here after those checks pass.

Locally checked on 2026-10-11: Compose configuration and Caddy validation pass;
an internal HTTP proxy test returned 401 without credentials and served both
health routes with credentials. Inventory creation and order creation through
the proxy reduced test stock from 10 to 8 in an isolated database. These checks
do not verify a real domain or public TLS certificate.
