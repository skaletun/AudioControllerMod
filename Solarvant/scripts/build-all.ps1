$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
foreach ($loader in @('fabric', 'neoforge', 'forge')) {
    Write-Host "Building Solarvant for $loader..." -ForegroundColor Green
    Push-Location (Join-Path $root $loader)
    try {
        & gradle clean build --stacktrace
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $loader" }
    } finally { Pop-Location }
}
