# Record a portfolio API demo

You can demonstrate this backend without a frontend. This kit includes ten
numbered Postman requests with assertions and a PowerShell recording walkthrough.
It demonstrates actual responses from your Java services, not simulated data.

## Start the separate demo stack

Run from the repository root with Docker running (Compose 2.24.4+):

```powershell
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml up --build -d --wait --wait-timeout 240
```

Inventory is at http://127.0.0.1:18081; Order is at http://127.0.0.1:18080.
These ports avoid your normal services on 8080/8081. Demo PostgreSQL uses memory
and never mounts the laptop volume. Data disappears when its container stops.
No public hosting or authentication is needed: the ports bind only to localhost.
Do not combine this configuration with compose.local.yaml.

## Option A: Postman walkthrough

Import `demo/portfolio.postman_collection.json` into Postman. The collection
already contains URLs, request bodies, variables, and response assertions.
No environment file is needed. Run requests in numbered order, using Send for
a screen recording or the collection runner for automated verification. The
tests use Postman's
[post-response scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts/).

Step 03 generates a fresh product ID; step 05 captures the created order ID.
Start again at step 03 to repeat the business flow. Repeating step 05 alone
places another real order and changes stock, so later quantity assertions fail.
Every run adds synthetic records only to the demo database.

| Steps | What viewers see | Expected result |
| --- | --- | --- |
| 01-02 | Service health | 200, UP |
| 03 | Create stock | 200, quantity 10 |
| 04 | Read stock and populate cache | 200, quantity 10 |
| 05 | Order two units | 200, CREATED |
| 06 | Read remaining stock | 200, quantity 8 |
| 07 | Look up saved order | 200, matching order ID |
| 08 | Try buying twenty units | 409, Not enough inventory |
| 09 | Try quantity zero | 400, quantity validation error |
| 10 | Check stock after rejections | 200, quantity still 8 |

For each step, show the method/URL, body when applicable, status code, response,
and Test Results. Successful POSTs currently return 200; the demo follows the
implemented controllers.

## Option B: terminal recording

For an automatic verified walkthrough:

```powershell
.\scripts\Invoke-ApiDemo.ps1
```

To build/start the stack as well, add `-StartStack`. For a presentation that
waits for Enter between requests:

```powershell
.\scripts\Invoke-ApiDemo.ps1 -PauseBetweenSteps
```

Use a fresh terminal at readable zoom and start recording after the Docker
build finishes. The script checks HTTP codes, quantities, health, and the saved
order. Unexpected results stop the demo. It saves real response evidence in
`demo/artifacts/last-run.json` (ignored by Git). Windows PowerShell 5.1 and
PowerShell 7 are supported by the HTTP error-handling code.

## A two-minute narration

| Time | Show | Say |
| --- | --- | --- |
| 0:00-0:15 | README architecture diagram | "I built two Spring Boot services. Each owns its PostgreSQL database, and Order calls Inventory through REST." |
| 0:15-0:35 | Health, create stock, read stock | "Both services are healthy. I'll create a product with ten units and read its stock." |
| 0:35-1:05 | Create order, remaining stock, lookup | "Ordering two units triggers stock reduction in Inventory and saves the order in Order's database. Eight units remain." |
| 1:05-1:30 | Oversized and invalid orders, unchanged stock | "Requests for too much stock return 409. Invalid quantities return 400. Neither rejected request changes stock." |
| 1:30-1:50 | InventoryConcurrencyTests and successful CI | "A separate PostgreSQL integration test starts ten reductions against five units. Exactly five succeed. CI runs tests, builds images, and checks healthy startup." |
| 1:50-2:00 | README reliability paragraph | "My next step is reservations and idempotency so partial failures and retries cannot leave stock reduced without an order." |

Record your real session with your preferred screen recorder, then put the
video on your portfolio next to the architecture diagram and repository link.
This kit prepares the walkthrough; it does not generate a video recording.

Do not describe sequential API requests as a concurrency proof or cached reads
as strongly consistent. The database row-lock tests are separate evidence.

## Stop afterward

```powershell
docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml down
```

This removes only demo containers/network. It does not delete volumes or touch
the running laptop stack. Its in-memory demo database is deliberately temporary.

## Verified results

For a real public catalog import followed by concurrent local ordering, see
[external API integration and traffic testing](external-api-testing.md).

Checked on 2026-10-11 against the isolated running services: the Windows
PowerShell 5.1 walkthrough passed all ten steps. Newman 6 ran the actual Postman
collection: ten requests, twenty assertions, zero failures. Generated JSON
evidence is available locally under demo/artifacts/ and is ignored by Git.
