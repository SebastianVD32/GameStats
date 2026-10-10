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
if ($batches.Count -ne 9) { throw 'Expected exactly nine Go batches' }
$utf8 = [System.Text.UTF8Encoding]::new($false)
foreach ($id in @('chess-1', 'chess-2', 'chess-3', 'basket-1', 'basket-2', 'basket-3')) {
    $match = @($batches | Where-Object { $_.matchId -eq $id })
    if ($match.Count -ne 1) { throw "Expected exactly one Go batch for $id" }
    [System.IO.File]::WriteAllText((Join-Path $exchange "$id.json"), ($match[0] | ConvertTo-Json -Depth 50), $utf8)
    docker compose -f $scalaCompose run --rm --no-deps --volume "${exchange}:/exchange" chess-cli "/exchange/$id.json" "/exchange/$id.result.json"
    if ($LASTEXITCODE -ne 0) { throw "Scala analysis failed for $id" }
    $actual = Get-Content -LiteralPath (Join-Path $exchange "$id.result.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($actual.matchId -ne $id) { throw "Unexpected result identity for $id" }
    $expectedPath = Join-Path $repo "scala-analytics/testdata/$id.expected.json"
    if (Test-Path -LiteralPath $expectedPath) {
        $expected = Get-Content -LiteralPath $expectedPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if (($actual | ConvertTo-Json -Depth 50 -Compress) -cne ($expected | ConvertTo-Json -Depth 50 -Compress)) {
            throw "Result differs from expected JSON for $id"
        }
    }
}
Write-Host 'Integration OK: Go produced 9 batches; Scala analyzed 3 chess and 3 basketball matches.'
Write-Host "Results: $exchange"
