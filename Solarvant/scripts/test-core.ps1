$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))
New-Item -Type Directory '.tmp-solarvant-tests' -Force | Out-Null
try {
    $sources = @(Get-ChildItem 'common/src/main/java/dev/solarvant/core' -Filter '*.java' | ForEach-Object FullName)
    $sources += (Resolve-Path 'common/src/main/java/dev/solarvant/engine/CanopyOcclusion.java').Path
    $sources += (Resolve-Path 'common/src/main/java/dev/solarvant/engine/RenderBudgetGovernor.java').Path
    $sources += (Resolve-Path 'common/src/main/java/dev/solarvant/engine/UploadPacer.java').Path
    $sources += (Resolve-Path 'common/src/main/java/dev/solarvant/engine/SectionTintCache.java').Path
    $sources += (Resolve-Path 'common/src/main/java/dev/solarvant/engine/RegionArena.java').Path
    $sources += (Resolve-Path 'common/src/main/java/dev/solarvant/engine/ReciprocalFaceCache.java').Path
    $sources += (Resolve-Path 'tests/dev/solarvant/tests/CoreTests.java').Path
    & javac --release 21 -d '.tmp-solarvant-tests' @sources
    if ($LASTEXITCODE -ne 0) { throw 'Core Java compilation failed' }
    & java -cp '.tmp-solarvant-tests' dev.solarvant.tests.CoreTests
    if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
} finally { Remove-Item '.tmp-solarvant-tests' -Force -Recurse -ErrorAction SilentlyContinue }
