[CmdletBinding()]
param(
    [switch]$StartStack,
    [switch]$PauseBetweenSteps
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$inventoryUrl = 'http://127.0.0.1:18081'
$orderUrl = 'http://127.0.0.1:18080'
$productId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$script:demoSteps = @()

if ($StartStack) {
    Push-Location $projectRoot
    try {
        # Docker prints progress to stderr even on success. PS 5.1 must use its exit code.
        $nativePreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & docker compose -p portfolio-demo -f compose.yaml -f compose.api-demo.yaml up --build -d --wait --wait-timeout 240
            $dockerExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $nativePreference }
        if ($dockerExit -ne 0) { throw 'Demo stack did not become healthy.' }
    } finally { Pop-Location }
}

function Invoke-DemoStep {
    param(
        [string]$Title,
        [string]$Explanation,
        [string]$Method,
        [string]$Uri,
        [object]$Body,
        [int]$ExpectedStatus = 200
    )
    Write-Host "`n$Title" -ForegroundColor Cyan
    Write-Host $Explanation
    Write-Host "$Method $Uri"
    $request = @{ Method = $Method; Uri = $Uri; UseBasicParsing = $true; TimeoutSec = 20 }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress
        Write-Host "Request: $json"
        $request.ContentType = 'application/json'
        $request.Body = $json
    }
    try {
        $response = Invoke-WebRequest @request
        $status = [int]$response.StatusCode
        $content = $response.Content
    } catch {
        $httpResponse = $_.Exception.Response
        if ($null -eq $httpResponse) { throw }
        $status = [int]$httpResponse.StatusCode
        # Windows PowerShell 5 uses HttpWebResponse; PowerShell 7 uses HttpResponseMessage.
        if ($httpResponse -is [System.Net.HttpWebResponse]) {
            $reader = New-Object System.IO.StreamReader($httpResponse.GetResponseStream())
            try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } else {
            $content = $httpResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        if ([string]::IsNullOrWhiteSpace($content)) { $content = $_.ErrorDetails.Message }
    }
    # PS 5.1 treats Actuator's vendor JSON content type as binary.
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
    if ($status -ne $ExpectedStatus) { throw "Expected HTTP $ExpectedStatus, received $status. $content" }
    Write-Host "HTTP $status" -ForegroundColor Green
    Write-Host $content
    $script:demoSteps += [pscustomobject]@{ title = $Title; method = $Method; url = $Uri; status = $status; response = $content }
    if ($PauseBetweenSteps) { [void](Read-Host 'Press Enter for the next step') }
    if ($status -eq 200) { return ($content | ConvertFrom-Json) }
}

Write-Host 'DISTRIBUTED ORDER PROCESSING | API DEMO' -ForegroundColor Cyan
Write-Host 'Two services. PostgreSQL stock protection. Redis reads. No frontend required.'
Write-Host 'Using the separate portfolio-demo stack on ports 18080 and 18081.'

$health = Invoke-DemoStep '1. Inventory is healthy' 'The Inventory app can reach its dependencies.' GET "$inventoryUrl/actuator/health"
if ($health.status -ne 'UP') { throw 'Inventory is not UP.' }
$health = Invoke-DemoStep '2. Order is healthy' 'The Order app is ready for requests.' GET "$orderUrl/actuator/health"
if ($health.status -ne 'UP') { throw 'Order is not UP.' }
$inventory = Invoke-DemoStep '3. Create stock' 'Create a new demo product with ten units.' POST "$inventoryUrl/inventory" @{ productId = $productId; productName = 'Portfolio demo headphones'; quantity = 10 }
if ($inventory.quantity -ne 10) { throw 'Expected ten initial units.' }
$inventory = Invoke-DemoStep '4. Read inventory' 'This read also populates the Redis cache.' GET "$inventoryUrl/inventory/$productId"
if ($inventory.quantity -ne 10) { throw 'Expected ten units before ordering.' }
$order = Invoke-DemoStep '5. Place an order' 'Order Service calls Inventory Service, reduces stock, and saves an order.' POST "$orderUrl/orders" @{ productId = $productId; quantity = 2 }
if ($order.status -ne 'CREATED' -or $order.quantity -ne 2) { throw 'Unexpected order response.' }
$inventory = Invoke-DemoStep '6. Verify remaining stock' 'Ten minus two equals eight; the earlier cache entry was evicted.' GET "$inventoryUrl/inventory/$productId"
if ($inventory.quantity -ne 8) { throw 'Expected eight remaining units.' }
$savedOrder = Invoke-DemoStep '7. Look up the saved order' 'Order Service reads the order from its own database.' GET "$orderUrl/orders/$($order.id)"
if ($savedOrder.id -ne $order.id) { throw 'Saved order ID did not match.' }
Invoke-DemoStep '8. Reject an oversized order' 'Twenty units exceed available stock: expect 409 Conflict.' POST "$orderUrl/orders" @{ productId = $productId; quantity = 20 } 409
Invoke-DemoStep '9. Reject invalid input' 'Quantity zero fails request validation: expect 400 Bad Request.' POST "$orderUrl/orders" @{ productId = $productId; quantity = 0 } 400
$inventory = Invoke-DemoStep '10. Verify rejected requests changed nothing' 'Stock is still eight after both rejected orders.' GET "$inventoryUrl/inventory/$productId"
if ($inventory.quantity -ne 8) { throw 'Rejected requests changed stock.' }

$outputDirectory = Join-Path $projectRoot 'demo/artifacts'
[void](New-Item -ItemType Directory -Path $outputDirectory -Force)
$outputFile = Join-Path $outputDirectory 'last-run.json'
[pscustomobject]@{
    verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    productId = $productId
    orderId = $order.id
    passed = $true
    steps = $script:demoSteps
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputFile -Encoding UTF8
Write-Host "`nPASS: all ten API steps verified." -ForegroundColor Green
Write-Host "Evidence saved to $outputFile"
Write-Host 'Concurrency is verified separately by the PostgreSQL integration tests.'
Write-Host 'Failure handling (reservations, compensation, Idempotency-Key) is stress-tested in docs/stress-and-resilience.md.'
