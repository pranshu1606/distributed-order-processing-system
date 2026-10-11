"""Fetch a public sample catalog, then exercise ONLY the isolated portfolio-demo APIs.

Uses Python's standard library. DummyJSON receives one GET per run; concurrent
traffic goes to localhost. Database verification uses read-only Docker/psql.
"""
import concurrent.futures
import json
import math
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

SOURCE_URL = "https://dummyjson.com/products?limit=3&select=id,title"
INVENTORY_URL = "http://127.0.0.1:18081"
ORDER_URL = "http://127.0.0.1:18080"
INITIAL_STOCK = 5
ATTEMPTS_PER_PRODUCT = 10
OUTPUT = pathlib.Path(__file__).resolve().parents[1] / "demo" / "artifacts" / "external-api-run.json"


def request(method, url, body=None):
    encoded = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=encoded, method=method,
                                 headers={"Content-Type": "application/json", "User-Agent": "OrderPortfolioDemo/1.0"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            status, content = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read().decode("utf-8")
    elapsed_ms = (time.perf_counter() - started) * 1000
    try:
        content = json.loads(content)
    except json.JSONDecodeError:
        pass
    return {"status": status, "body": content, "durationMs": round(elapsed_ms, 2)}


def database_rows(database, query):
    # Fixed demo container, read-only SQL, and no shell interpolation.
    result = subprocess.run([
        "docker", "exec", "portfolio-demo-postgres-1", "psql", "-U", "postgres",
        "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query
    ], capture_output=True, text=True, check=True, timeout=30)
    return result.stdout.strip().splitlines()


def place_order(product_id):
    try:
        return request("POST", ORDER_URL + "/orders", {"productId": product_id, "quantity": 1})
    except (OSError, TimeoutError) as error:
        return {"status": 0, "body": str(error), "durationMs": None}


def main():
    for base_url in (INVENTORY_URL, ORDER_URL):
        health = request("GET", base_url + "/actuator/health")
        if health["status"] != 200 or health["body"].get("status") != "UP":
            raise RuntimeError("Start the isolated portfolio-demo stack before running this script.")

    print("Fetching three sample products from DummyJSON (one external GET)...", flush=True)
    source = request("GET", SOURCE_URL)
    if source["status"] != 200 or not isinstance(source["body"], dict):
        raise RuntimeError(f"Catalog request failed: {source}")
    products = source["body"].get("products")
    if not isinstance(products, list) or len(products) != 3:
        raise RuntimeError("Expected three products in the external catalog response.")
    # Validate the entire catalog before making local inventory writes.
    for product in products:
        if not isinstance(product.get("id"), int) or not isinstance(product.get("title"), str) or not product["title"].strip():
            raise RuntimeError("External catalog contains an invalid product ID/title.")

    base_id = time.time_ns() // 1_000_000 * 1000
    mapped = []
    for offset, product in enumerate(products):
        local_id = base_id + offset
        created = request("POST", INVENTORY_URL + "/inventory", {
            "productId": local_id, "productName": product["title"], "quantity": INITIAL_STOCK
        })
        if created["status"] != 200 or created["body"].get("quantity") != INITIAL_STOCK:
            raise RuntimeError(f"Inventory creation failed: {created}")
        mapped.append({"sourceId": product["id"], "title": product["title"], "localProductId": local_id})
        print(f"Imported {product['title']}: five LOCAL demo units (product {local_id})", flush=True)

    results = []
    started = time.perf_counter()
    for product in mapped:
        print(f"Sending ten order attempts with ten workers for {product['title']}...", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(place_order, product["localProductId"]) for _ in range(ATTEMPTS_PER_PRODUCT)]
            responses = [future.result() for future in futures]
        successes = sum(response["status"] == 200 for response in responses)
        conflicts = sum(response["status"] == 409 for response in responses)
        results.append({**product, "successfulOrders": successes, "stockRejections": conflicts, "responses": responses})
        print(f"  {successes} accepted, {conflicts} rejected for insufficient stock", flush=True)
    traffic_seconds = time.perf_counter() - started

    ids = ",".join(str(product["localProductId"]) for product in mapped)
    inventory_rows = database_rows("inventory_db", f"SELECT product_id, quantity FROM inventory WHERE product_id IN ({ids}) ORDER BY product_id;")
    order_rows = database_rows("order_db", f"SELECT product_id, count(*), sum(quantity) FROM orders WHERE product_id IN ({ids}) GROUP BY product_id ORDER BY product_id;")
    stocks = {int(row.split("|")[0]): int(row.split("|")[1]) for row in inventory_rows}
    persisted = {int(row.split("|")[0]): {"count": int(row.split("|")[1]), "units": int(row.split("|")[2])} for row in order_rows}
    for product in results:
        product_id = product["localProductId"]
        product["databaseStock"] = stocks.get(product_id)
        product["persistedOrders"] = persisted.get(product_id, {"count": 0, "units": 0})
        # Report cached reads separately; PostgreSQL is the stock authority.
        product["cachedInventoryRead"] = request("GET", f"{INVENTORY_URL}/inventory/{product_id}")

    responses = [response for product in results for response in product["responses"]]
    durations = sorted(response["durationMs"] for response in responses if response["durationMs"] is not None)
    passed = all(
        product["successfulOrders"] == INITIAL_STOCK
        and product["stockRejections"] == ATTEMPTS_PER_PRODUCT - INITIAL_STOCK
        and product["databaseStock"] == 0
        and product["persistedOrders"] == {"count": INITIAL_STOCK, "units": INITIAL_STOCK}
        for product in results
    )
    summary = {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(), "passed": passed,
        "sourceUrl": SOURCE_URL, "sourceRequest": source, "externalRequests": 1,
        "concurrentWorkers": 10, "orderAttempts": len(responses),
        "successfulOrders": sum(response["status"] == 200 for response in responses),
        "expectedStockRejections": sum(response["status"] == 409 for response in responses),
        "unexpectedResponses": sum(response["status"] not in (200, 409) for response in responses),
        "trafficDurationSeconds": round(traffic_seconds, 2),
        "observedOrderAttemptsPerSecond": round(len(responses) / traffic_seconds, 2),
        "orderResponseTimeMs": {
            "mean": round(sum(durations) / len(durations), 2) if durations else None,
            "p95NearestRank": durations[math.ceil(0.95 * len(durations)) - 1] if durations else None,
            "max": max(durations) if durations else None
        }, "products": results
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items() if key not in ("sourceRequest", "products")}, indent=2))
    print("Database checks: zero stock and exactly five persisted orders per imported product.")
    print(f"Full evidence: {OUTPUT}")
    if not passed:
        raise RuntimeError("External API traffic verification failed; inspect the saved evidence.")


if __name__ == "__main__":
    main()
