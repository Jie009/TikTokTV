# POST cookie to CookieHandoffServer on port 8899 (same as login overlay form).
# Prerequisite: app open -> menu -> login -> handoff screen waiting.

param(
    [string]$CookieFile = (Join-Path (Join-Path $PSScriptRoot "..") ".cursor\tmp_cookie.txt"),
    [int]$Port = 8899
)

$cookie = (Get-Content -LiteralPath $CookieFile -Raw).Trim()
if (-not $cookie) {
    Write-Error "Cookie file empty: $CookieFile"
    exit 1
}

adb reverse "tcp:$Port" "tcp:$Port" | Out-Null

$body = "cookie=" + [uri]::EscapeDataString($cookie)
try {
    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -Method POST -Body $body `
        -ContentType "application/x-www-form-urlencoded" -UseBasicParsing -TimeoutSec 15
    Write-Host "POST OK HTTP $($resp.StatusCode), length=$($resp.Content.Length)"
} catch {
    Write-Error "POST failed: $_ (open app login overlay first)"
    exit 1
}
