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
docker compose -f $goCompose run --rm --no-deps --volume "${fixtures}:/fixtures:ro" --volume "${exchange}:/exchange" go-ingestion -input /fixtures/interleaved-events.jsonl -serve=false -output /exchange/ready-matches.jsonl
if ($LASTEXITCODE -ne 0) { throw 'Go ingestion failed' }

$batches = @(Get-Content -LiteralPath (Join-Path $exchange 'ready-matches.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
$chess = @($batches | Where-Object { $_.gameId -eq 'CHESS' -and $_.matchId -eq 'chess-1' })
if ($chess.Count -ne 1) { throw 'Expected exactly one Go batch for chess-1' }
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $exchange 'chess-1.json'), ($chess[0] | ConvertTo-Json -Depth 50), $utf8)
docker compose -f $scalaCompose run --rm --no-deps --volume "${exchange}:/exchange" chess-cli /exchange/chess-1.json /exchange/chess-1.result.json
if ($LASTEXITCODE -ne 0) { throw 'Scala analysis failed' }
$actual = Get-Content -LiteralPath (Join-Path $exchange 'chess-1.result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($actual.totalMoves -ne 5 -or $actual.totalCaptures -ne 1 -or $actual.durationMs -ne 9000 -or
    $actual.winnerPlayerId -ne 'chess-alice' -or -not $actual.players[0].timePressure) {
    throw 'Unexpected chess analysis result'
}
Write-Host "Integration OK: Go produced $($batches.Count) batches; Scala analyzed chess-1."
Write-Host "Result: $(Join-Path $exchange 'chess-1.result.json')"
