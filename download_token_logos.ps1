# Batch download token logos from BscScan
# Data source: CoinGecko API (binance-smart-chain tokens)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputDir = Join-Path $scriptDir "app\src\main\assets\token_logos"
$jsonFile = Join-Path $scriptDir "bsc_addresses.json"

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$tokens = Get-Content $jsonFile -Raw | ConvertFrom-Json
Write-Host "Loaded $($tokens.Count) BSC addresses from CoinGecko" -ForegroundColor Cyan

$tokens = $tokens | Select-Object -Unique
Write-Host "After dedup: $($tokens.Count)" -ForegroundColor Cyan

$wc = New-Object System.Net.WebClient
$wc.Headers.Add('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36')
$downloaded = 0
$skipped = 0
$failed = 0
$total = $tokens.Count

Write-Host "===== Downloading $total token logos from BscScan =====" -ForegroundColor Cyan

foreach ($token in $tokens) {
    $fileName = "$token.png"
    $filePath = Join-Path $outputDir $fileName
    $url = "https://bscscan.com/token/images/$token.png"
    
    if (Test-Path $filePath) {
        $sz = (Get-Item $filePath).Length
        if ($sz -gt 100) {
            $skipped++
            continue
        } else {
            Remove-Item $filePath -Force
        }
    }
    
    try {
        $wc.DownloadFile($url, $filePath)
        $size = (Get-Item $filePath).Length
        if ($size -gt 100) {
            $downloaded++
        } else {
            Remove-Item $filePath -Force
            $failed++
        }
    } catch {
        $failed++
        if (Test-Path $filePath) { Remove-Item $filePath -Force }
    }
    
    $idx = $downloaded + $skipped + $failed
    if ($idx % 10 -eq 0) {
        Write-Host "Progress: $idx/$total (OK: $downloaded, Skip: $skipped, Fail: $failed)" -ForegroundColor Gray
    }
    
    Start-Sleep -Milliseconds 150
}

Write-Host ""
Write-Host "===== Download Complete =====" -ForegroundColor Cyan
Write-Host "OK: $downloaded" -ForegroundColor Green
Write-Host "Skip: $skipped" -ForegroundColor Gray
Write-Host "Fail: $failed" -ForegroundColor Red
Write-Host "Total: $total" -ForegroundColor Cyan

$finalCount = (Get-ChildItem -Path $outputDir -Filter "*.png" | Where-Object { $_.Length -gt 100 }).Count
Write-Host "Final valid logo count: $finalCount" -ForegroundColor Green