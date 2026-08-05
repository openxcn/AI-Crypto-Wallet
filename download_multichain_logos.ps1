# Multi-chain token logo downloader (fast version)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputDir = Join-Path $scriptDir "app\src\main\assets\token_logos"
$jsonFile = Join-Path $scriptDir "multichain_tokens.json"

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$allChains = Get-Content $jsonFile -Raw | ConvertFrom-Json

$explorerUrls = @{
    'ethereum'           = 'https://etherscan.io/token/images/{0}.png'
    'binance-smart-chain'= 'https://bscscan.com/token/images/{0}.png'
    'polygon-pos'        = 'https://polygonscan.com/token/images/{0}.png'
    'arbitrum-one'       = 'https://arbiscan.io/token/images/{0}.png'
    'avalanche'          = 'https://snowtrace.io/token/images/{0}.png'
    'fantom'             = 'https://ftmscan.com/token/images/{0}.png'
    'celo'               = 'https://celoscan.io/token/images/{0}.png'
    'moonbeam'           = 'https://moonscan.io/token/images/{0}.png'
    'kava'               = 'https://kavascan.com/token/images/{0}.png'
}

$limits = @{
    'ethereum'    = 100
    'polygon-pos' = 100
    'arbitrum-one'= 100
    'avalanche'   = 100
    'celo'        = 30
    'harmony-shard-0' = 30
}

$wc = New-Object System.Net.WebClient
$wc.Headers.Add('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36')

$totalDownloaded = 0
$totalSkipped = 0
$totalFailed = 0

foreach ($chain in $allChains.PSObject.Properties) {
    $chainName = $chain.Name
    $addresses = $chain.Value
    
    if (-not $explorerUrls.ContainsKey($chainName)) { continue }
    if ($chainName -eq 'binance-smart-chain') { continue }
    
    $limit = $limits[$chainName]
    if ($limit) { $addresses = $addresses | Select-Object -First $limit }
    
    $urlTemplate = $explorerUrls[$chainName]
    $count = $addresses.Count
    
    Write-Host "$chainName : $count tokens" -ForegroundColor Cyan
    
    $ok = 0; $sk = 0; $fl = 0
    
    foreach ($addr in $addresses) {
        $fileName = "$addr.png"
        $filePath = Join-Path $outputDir $fileName
        $url = $urlTemplate -f $addr
        
        if (Test-Path $filePath) {
            $sz = (Get-Item $filePath).Length
            if ($sz -gt 100) { $sk++; $totalSkipped++; continue }
            else { Remove-Item $filePath -Force }
        }
        
        try {
            $wc.DownloadFile($url, $filePath)
            $size = (Get-Item $filePath).Length
            if ($size -gt 100) { $ok++; $totalDownloaded++ }
            else { Remove-Item $filePath -Force; $fl++; $totalFailed++ }
        } catch {
            $fl++; $totalFailed++
            if (Test-Path $filePath) { Remove-Item $filePath -Force }
        }
    }
    
    Write-Host "  -> OK:$ok Skip:$sk Fail:$fl" -ForegroundColor Green
}

Write-Host "`n===== DONE =====" -ForegroundColor Cyan
Write-Host "Total OK: $totalDownloaded, Skip: $totalSkipped, Fail: $totalFailed" -ForegroundColor Green

$finalCount = (Get-ChildItem -Path $outputDir -Filter "*.png" | Where-Object { $_.Length -gt 100 }).Count
$totalSize = [math]::Round(((Get-ChildItem -Path $outputDir -Filter "*.png" | Where-Object { $_.Length -gt 100 } | Measure-Object -Property Length -Sum).Sum / 1KB), 1)
Write-Host "Final: $finalCount logos ($totalSize KB)" -ForegroundColor Green