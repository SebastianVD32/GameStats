$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$scalaCompose = Join-Path $repo 'scala-analytics/compose.yaml'
$goCompose = Join-Path $repo 'compose.yaml'
$fixtures = Join-Path $repo 'go-ingestion/testdata/valid'
# A unique directory prevents Go's append-only FileSink from mixing repeated runs.
$exchange = Join-Path $repo ('scala-analytics/output/integration-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $exchange -Force | Out-Null

docker compose -f $goCompose build go-ingestion
if ($LASTEXITCODE -ne 0) { throw 'Go Docker build failed' }
docker compose -f $scalaCompose build chess-cli
if ($LASTEXITCODE -ne 0) { throw 'Scala Docker build failed' }
docker compose -f $goCompose run --rm --no-deps -e ANALYZER_URL= --volume "${fixtures}:/fixtures:ro" --volume "${exchange}:/exchange" go-ingestion -input /fixtures/interleaved-events.jsonl -serve=false -output /exchange/ready-matches.jsonl
if ($LASTEXITCODE -ne 0) { throw 'Go ingestion failed' }

$batches = @(Get-Content -LiteralPath (Join-Path $exchange 'ready-matches.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
if ($batches.Count -ne 12) { throw "Expected exactly twelve Go batches; got $($batches.Count)" }
$utf8 = [System.Text.UTF8Encoding]::new($false)
foreach ($id in @('chess-1', 'chess-2', 'chess-3', 'basket-1', 'basket-2', 'basket-3', 'race-1', 'race-2', 'race-3', 'pacman-1', 'pacman-2', 'pacman-3')) {
    $match = @($batches | Where-Object { $_.matchId -eq $id })
    if ($match.Count -ne 1) { throw "Expected exactly one Go batch for $id" }
    [System.IO.File]::WriteAllText((Join-Path $exchange "$id.json"), ($match[0] | ConvertTo-Json -Depth 50), $utf8)
    if ($id -eq 'race-3') {
        # Preserve the inconsistent Go fixture and verify the analytical rejection.
        $diagnosticPath = Join-Path $exchange 'race-3.error.txt'
        # Compose may forward container diagnostics through stdout or stderr.
        # Capture both as text, avoiding PowerShell's formatted ErrorRecord files.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $diagnosticLines = @(docker compose -f $scalaCompose run --rm -T --no-deps --volume "${exchange}:/exchange" chess-cli "/exchange/$id.json" 2>&1 |
                ForEach-Object { $_.ToString() })
            $analysisExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousPreference }
        $diagnostic = $diagnosticLines -join [Environment]::NewLine
        [System.IO.File]::WriteAllText($diagnosticPath, $diagnostic + [Environment]::NewLine, $utf8)
        if ($analysisExitCode -ne 1) { throw "Expected race-3 classification rejection with exit code 1; got $analysisExitCode. Diagnostic: $diagnosticPath`n$diagnostic" }
        if (-not $diagnostic.Contains('Classification inconsistency')) { throw "Missing race-3 inconsistency diagnostic. Captured output: $diagnosticPath`n$diagnostic" }
        if (-not $diagnostic.Contains('ClassificationEntry(race-alice,2)') -or
            -not $diagnostic.Contains('ClassificationEntry(race-dan,3)')) { throw 'Unexpected calculated race-3 order' }
        continue
    }
    docker compose -f $scalaCompose run --rm --no-deps --volume "${exchange}:/exchange" chess-cli "/exchange/$id.json" "/exchange/$id.result.json"
    if ($LASTEXITCODE -ne 0) { throw "Scala analysis failed for $id" }
    $actual = Get-Content -LiteralPath (Join-Path $exchange "$id.result.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($actual.matchId -ne $id) { throw "Unexpected result identity for $id" }
    $expectedPath = Join-Path $repo "scala-analytics/testdata/$id.expected.json"
    if ($id -like 'pacman-*') {
        $expectedPath = Join-Path $repo "scala-analytics/testdata/go-$id.expected.json"
    }
    if (Test-Path -LiteralPath $expectedPath) {
        $expected = Get-Content -LiteralPath $expectedPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if (($actual | ConvertTo-Json -Depth 50 -Compress) -cne ($expected | ConvertTo-Json -Depth 50 -Compress)) {
            throw "Result differs from expected JSON for $id"
        }
    }
}
Write-Host 'Integration OK: Go produced 12 batches; Scala analyzed 11 matches and rejected inconsistent race-3.'
Write-Host "Results: $exchange"
