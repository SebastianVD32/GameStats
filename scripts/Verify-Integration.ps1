[CmdletBinding()]
param([int]$StartupTimeoutSeconds = 120)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = 'gamestats-verify-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$output = Join-Path $repo "scala-analytics/output/$project"
New-Item -ItemType Directory -Path $output -Force | Out-Null
Start-Transcript -Path (Join-Path $output 'execution.log') | Out-Null
$compose = Join-Path $output 'compose.json'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$checks = [System.Collections.Generic.List[string]]::new()
$jobs = @()
$failed = $false
$created = $false
function Pass([string]$message) { $checks.Add($message); Write-Host "PASS: $message" }
function Native([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE): $($Arguments -join ' ')" }
}
function Compose([string[]]$Arguments) { Native (@('compose', '-p', $project, '-f', $compose) + $Arguments) }
function Canonical($value) {
    if ($null -eq $value) { return 'null' }
    if ($value -is [System.Management.Automation.PSCustomObject] -or $value -is [System.Collections.IDictionary]) {
        $names = if ($value -is [System.Collections.IDictionary]) { @($value.Keys) } else { @($value.PSObject.Properties.Name) }
        return '{' + (($names | Sort-Object | ForEach-Object {
            ($_ | ConvertTo-Json -Compress) + ':' + (Canonical $value.$_)
        }) -join ',') + '}'
    }
    if ($value -is [array]) { return '[' + (($value | ForEach-Object { Canonical $_ }) -join ',') + ']' }
    return ($value | ConvertTo-Json -Compress)
}
function Equal($actual, $expected, [string]$label) {
    if ((Canonical $actual) -cne (Canonical $expected)) {
        [System.IO.File]::WriteAllText((Join-Path $output 'mismatch.json'),
            (@{label=$label; actual=$actual; expected=$expected} | ConvertTo-Json -Depth 100), $utf8)
        throw "Unexpected result: $label (see mismatch.json)"
    }
}
function Request([string]$method, [string]$uri, $body = $null, [int]$status = 200) {
    $parameters = @{Method=$method; Uri=$uri; UseBasicParsing=$true; TimeoutSec=20; ErrorAction='Stop'}
    if ($null -ne $body) { $parameters.ContentType='application/json'; $parameters.Body=[System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 100 -Compress)) }
    try { $response = Invoke-WebRequest @parameters; $code = [int]$response.StatusCode }
    catch {
        if ($null -eq $_.Exception.Response) { throw }
        $code = [int]$_.Exception.Response.StatusCode
        if ($code -ne $status) { throw "HTTP $code instead of ${status}: $method $uri -- $($_.ErrorDetails.Message)" }
        return
    }
    if ($code -ne $status) { throw "HTTP $code instead of ${status}: $method $uri" }
    if ($response.Content) { return ($response.Content | ConvertFrom-Json) }
}
function Wait-Ready([string]$base) {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    do {
        try { Request 'GET' "$base/healthz" | Out-Null; return } catch { Start-Sleep -Milliseconds 500 }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Service not ready before timeout: $base"
}
function Clone-Value($value, [string]$prefix, $events) {
    $json = $value | ConvertTo-Json -Depth 100 -Compress
    $ids = @($events | ForEach-Object { $_.eventId; $_.matchId } | Sort-Object -Unique)
    foreach ($id in $ids) {
        $json = $json.Replace(($id | ConvertTo-Json -Compress), (($prefix + $id) | ConvertTo-Json -Compress))
    }
    return ($json | ConvertFrom-Json)
}
function Check-Accumulates([int]$factor) {
    foreach ($entry in @(@('pac-alice',1460), @('pac-bob',60), @('pac-carol',3050))) {
        $expected = @{playerId=$entry[0]; completedMatches=$factor; totalScore=([int]$entry[1]*$factor); bestScore=[int]$entry[1]}
        Equal (Request 'GET' "$scala/api/v1/pacman/players/$($entry[0])") $expected "Pac-Man accumulated $($entry[0])"
    }
    $colors = @(Request 'GET' "$scala/api/v1/chess/colors")
    foreach ($color in $colors) {
        if ($color.wins -ne $factor -or $color.completedMatches -ne 3*$factor -or [Math]::Abs($color.winPercentage - 100.0/3) -gt 0.000001) {
            throw 'Invalid chess color aggregate'
        }
    }
    if ($colors.Count -ne 2) { throw 'Missing chess color aggregates' }
}
try {
    Native @('info', '--format', '{{.ServerVersion}}')
    $raw = @(& docker compose -f (Join-Path $repo 'compose.yaml') config --format json)
    if ($LASTEXITCODE -ne 0) { throw 'Root Compose configuration is invalid' }
    $config = ($raw -join "`n") | ConvertFrom-Json
    $config.PSObject.Properties.Remove('name')
    foreach ($volume in $config.volumes.PSObject.Properties) { $volume.Value.name = "${project}_$($volume.Name)" }
    foreach ($service in $config.services.PSObject.Properties) {
        $port = if ($service.Name -eq 'go-ingestion') { 8080 } else { 8081 }
        $service.Value.ports = @(@{target=$port; published='0'; host_ip='127.0.0.1'; protocol='tcp'})
        $service.Value.image = "${project}-$($service.Name):local"
        $service.Value.restart = 'no'
    }
    # Resolved Compose assigns an explicit default network name; isolate it as well.
    foreach ($network in $config.networks.PSObject.Properties) { $network.Value.name = "${project}_$($network.Name)" }
    $goCheck = @'
set -eu
cp -R /source/. /work/
test -z "$(gofmt -l cmd internal)"
go vet ./...
go test -race -coverprofile=/work/coverage.out ./...
go build -trimpath -o /work/gamestats ./cmd/gamestats
/work/gamestats -input testdata/valid/interleaved-events.jsonl -serve=false -output /work/ready.jsonl
test "$(wc -l < /work/ready.jsonl)" -eq 12
for fixture in testdata/invalid/*; do
  if /work/gamestats -input "$fixture" -serve=false -output /work/invalid.jsonl; then
    echo "Unexpected acceptance: $fixture"; exit 1
  fi
done
'@
    $config.services | Add-Member -MemberType NoteProperty -Name 'go-check' -Value @{
        image='golang:1.24'; profiles=@('checks'); working_dir='/work';
        volumes=@(@{type='bind'; source=(Join-Path $repo 'go-ingestion'); target='/source'; read_only=$true});
        command=@('sh','-ec',$goCheck.Replace('$','$$'))
    }
    [System.IO.File]::WriteAllText($compose, ($config | ConvertTo-Json -Depth 100), $utf8)
    Compose @('config', '--quiet')
    Pass 'Root Compose validated; test ports, network, images and volumes isolated'
    $created = $true
    Compose @('run', '--rm', '--no-deps', 'go-check')
    Pass 'Go format, vet, race tests, coverage, compilation, 12 batches and all invalid fixtures'
    Compose @('build', 'go-ingestion', 'scala-analysis')
    Pass 'Both runtime images built; Scala unit tests and JAR packaging passed in Docker build'
    Compose @('up', '-d', '--wait', '--wait-timeout', "$StartupTimeoutSeconds", 'go-ingestion', 'scala-analysis')
    $goPort = @(Compose @('port', 'go-ingestion', '8080'))[-1].ToString().Trim().Split(':')[-1]
    $scalaPort = @(Compose @('port', 'scala-analysis', '8081'))[-1].ToString().Trim().Split(':')[-1]
    $go = "http://127.0.0.1:$goPort"
    $scala = "http://127.0.0.1:$scalaPort"
    Wait-Ready $go; Wait-Ready $scala
    Pass "Services ready: Go $go; Scala $scala"
    Equal (Request 'GET' "$scala/api/v1/pacman/players/unknown") @{playerId='unknown'; completedMatches=0; totalScore=0; bestScore=$null} 'Empty player aggregate'
    $emptyColors = @(Request 'GET' "$scala/api/v1/chess/colors")
    if ($emptyColors.Count -ne 2 -or @($emptyColors | Where-Object { $_.completedMatches -ne 0 -or $null -ne $_.winPercentage }).Count -ne 0) { throw 'Invalid empty chess aggregates' }
    $events = @(Get-Content (Join-Path $repo 'go-ingestion/testdata/valid/interleaved-events.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    $matchGroups = @($events | Group-Object matchId)
    $expectedResults = @{}
    foreach ($group in $matchGroups) {
        $id = $group.Name
        $file = if ($id -like 'pacman-*') { "go-$id.expected.json" } else { "$id.expected.json" }
        $expectedResults[$id] = Get-Content (Join-Path $repo "scala-analytics/testdata/$file") -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    foreach ($event in $events) {
        $status = if ($event.matchId -eq 'race-3' -and $event.type -eq 'MATCH_FINISHED') { 502 } else { 201 }
        Request 'POST' "$go/api/v1/events" $event $status | Out-Null
        if ($event.type -eq 'MATCH_STARTED') { Request 'GET' "$scala/api/v1/matches/$($event.matchId)" $null 404 | Out-Null }
    }
    Request 'GET' "$scala/api/v1/matches/race-3" $null 404 | Out-Null
    # The original fixture remains unchanged: only repair this test's final event.
    $raceFinish = $events | Where-Object { $_.matchId -eq 'race-3' -and $_.type -eq 'MATCH_FINISHED' }
    $raceFinish.data.classification = $expectedResults['race-3'].classification
    Request 'POST' "$go/api/v1/events" $raceFinish 201 | Out-Null
    foreach ($group in $matchGroups) {
        $id = $group.Name
        Equal (Request 'GET' "$scala/api/v1/matches/$id") $expectedResults[$id] "Full result $id"
        $batch = Request 'GET' "$go/api/v1/matches/$id"
        Equal $batch.events @($group.Group) "Go grouped events $id"
    }
    Check-Accumulates 1
    Pass 'All 12 interleaved matches traversed Go -> Scala; full independent results and aggregates match'
    Pass 'Inconsistent race-3 rejected with 502, no partial result; corrected final event retried successfully'
    Request 'POST' "$go/api/v1/events" $events[0] 409 | Out-Null
    foreach ($file in Get-ChildItem (Join-Path $repo 'go-ingestion/testdata/invalid') -File) {
        $bad = @(Get-Content $file.FullName -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
        $bad = @(Clone-Value $bad ("negative-$($file.BaseName)-") $bad)
        $rejected = $false
        foreach ($event in $bad) {
            try { Request 'POST' "$go/api/v1/events" $event 201 | Out-Null }
            catch {
                if ($_.Exception.Message -notmatch 'HTTP (400|409|422) instead of 201:') { throw }
                $rejected=$true; break
            }
        }
        if (-not $rejected) { throw "Invalid fixture accepted: $($file.Name)" }
        foreach ($id in @($bad | ForEach-Object { $_.matchId } | Sort-Object -Unique)) {
            Request 'GET' "$scala/api/v1/matches/$id" $null 404 | Out-Null
        }
    }
    Pass 'HTTP duplicate, missing fields, invalid references and all negative fixtures rejected without stored results'
    # One job per match; each job preserves its own sequence while matches run concurrently.
    foreach ($group in $matchGroups) {
        $batchEvents = @(Clone-Value @($group.Group) 'concurrent-' @($group.Group))
        if ($group.Name -eq 'pacman-1') { $batchEvents[-1].data.reason = 'USER_STOPPED' }
        $payload = ConvertTo-Json -InputObject $batchEvents -Depth 100 -Compress
        $jobs += Start-Job -ArgumentList $go,$payload -ScriptBlock {
            param($base,$json)
            $ErrorActionPreference='Stop'
            foreach ($event in ($json | ConvertFrom-Json)) {
                try {
                    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/v1/events" -ContentType 'application/json' -Body ([System.Text.Encoding]::UTF8.GetBytes(($event | ConvertTo-Json -Depth 100 -Compress))) -TimeoutSec 30
                } catch { throw "Concurrent $($event.matchId) sequence $($event.sequence): $($_.Exception.Message) $($_.ErrorDetails.Message)" }
                if ([int]$response.StatusCode -ne 201) { throw 'Concurrent event rejected' }
            }
            'OK'
        }
    }
    $completed = @($jobs | Wait-Job -Timeout 180)
    if ($completed.Count -ne $jobs.Count) { throw 'Concurrent requests timed out' }
    foreach ($job in $jobs) {
        $result = @(Receive-Job $job -ErrorAction Stop)
        if ($job.State -ne 'Completed' -or $result -notcontains 'OK') { throw 'Concurrent request job failed' }
    }
    foreach ($group in $matchGroups) {
        Equal (Request 'GET' "$scala/api/v1/matches/concurrent-$($group.Name)") (Clone-Value $expectedResults[$group.Name] 'concurrent-' @($group.Group)) "Concurrent full result $($group.Name)"
    }
    Check-Accumulates 2
    Pass '12 concurrent matches preserve per-match order, full results and cross-match aggregates'
    # Restart Go to clear only its documented temporary ingestion/deduplication state.
    Compose @('restart', 'go-ingestion')
    $goPort = @(Compose @('port', 'go-ingestion', '8080'))[-1].ToString().Trim().Split(':')[-1]
    $go = "http://127.0.0.1:$goPort"
    Wait-Ready $go
    foreach ($event in $events) { Request 'POST' "$go/api/v1/events" $event 201 | Out-Null }
    Check-Accumulates 2
    Pass 'Complete matches replayed through restarted Go without duplicate Scala results or aggregates'
    # Concurrent identical Scala deliveries exercise the durable store separately from Go ingestion.
    $batch = Request 'GET' "$go/api/v1/matches/pacman-1"
    $retryJobs = @()
    foreach ($n in 1..4) {
        $retryJobs += Start-Job -ArgumentList $scala,($batch | ConvertTo-Json -Depth 100 -Compress) -ScriptBlock {
            param($base,$json)
            $ErrorActionPreference='Stop'
            Invoke-RestMethod -Method Post -Uri "$base/api/v1/matches" -ContentType 'application/json' -Body $json -TimeoutSec 30 | Out-Null
            'OK'
        }
        $jobs += $retryJobs[-1]
    }
    if (@($retryJobs | Wait-Job -Timeout 90).Count -ne 4) { throw 'Concurrent retries timed out' }
    foreach ($job in $retryJobs) { if (@(Receive-Job $job -ErrorAction Stop) -notcontains 'OK') { throw 'Retry failed' } }
    $batch.events[-1].data.reason = 'USER_STOPPED'
    Request 'POST' "$scala/api/v1/matches" $batch 409 | Out-Null
    Check-Accumulates 2
    Compose @('restart', 'scala-analysis')
    $scalaPort = @(Compose @('port', 'scala-analysis', '8081'))[-1].ToString().Trim().Split(':')[-1]
    $scala = "http://127.0.0.1:$scalaPort"
    Wait-Ready $scala
    $persistedResults = @{}
    foreach ($group in $matchGroups) {
        $persistedResults[$group.Name] = Request 'GET' "$scala/api/v1/matches/$($group.Name)"
        $persistedResults["concurrent-$($group.Name)"] = Request 'GET' "$scala/api/v1/matches/concurrent-$($group.Name)"
        Equal $persistedResults[$group.Name] $expectedResults[$group.Name] "Persisted $($group.Name)"
        Equal $persistedResults["concurrent-$($group.Name)"] (Clone-Value $expectedResults[$group.Name] 'concurrent-' @($group.Group)) "Persisted concurrent $($group.Name)"
    }
    [System.IO.File]::WriteAllText((Join-Path $output 'actual-results.json'), ($persistedResults | ConvertTo-Json -Depth 100), $utf8)
    Check-Accumulates 2
    Pass 'Concurrent identical retries, conflicting batch rejection and all 24 results/aggregates after Scala restart'
    $logs = @(Compose @('logs', '--no-color', 'go-ingestion', 'scala-analysis'))
    [System.IO.File]::WriteAllText((Join-Path $output 'services.log'), ($logs -join "`n"), $utf8)
    if (($logs -join "`n") -match 'Request failed:|Exception in thread|panic:') { throw 'Unexpected service error in logs' }
    Pass 'Service logs reviewed; no unexpected storage failures, exceptions or Go panics'
} catch {
    $failed = $true
    Write-Host "FAIL: $($_.Exception.Message)"
    Write-Host $_.ScriptStackTrace
    if ($created) {
        try { Compose @('logs', '--no-color') 2>&1 | Out-File (Join-Path $output 'failure.log') -Encoding utf8 } catch { Write-Warning $_ }
    }
} finally {
    foreach ($job in $jobs) { $job | Stop-Job -ErrorAction SilentlyContinue; $job | Remove-Job -Force -ErrorAction SilentlyContinue }
    if ($created) {
        try { Compose @('down', '--volumes', '--remove-orphans', '--rmi', 'local', '--timeout', '15') }
        catch { $failed=$true; Write-Host "FAIL: isolated resource cleanup: $_" }
        # Explicitly tagged test images are not removed by --rmi local.
        foreach ($service in @('go-ingestion','scala-analysis')) {
            try {
                $imageId = @(Native @('image','ls','--quiet',"${project}-${service}:local"))
                if ($imageId.Count -gt 0) { Native @('image','rm',"${project}-${service}:local") }
            } catch { $failed=$true; Write-Host "FAIL: test image cleanup: $_" }
        }
    }
    $summary = [ordered]@{project=$project; passed=$checks.ToArray(); failed=$failed; output=$output}
    [System.IO.File]::WriteAllText((Join-Path $output 'summary.json'), ($summary | ConvertTo-Json -Depth 10), $utf8)
    Write-Host "Verification: $($checks.Count) groups passed; failed=$failed. Evidence: $output"
    Stop-Transcript | Out-Null
}
if ($failed) { exit 1 }
Write-Host 'Integration verification PASSED.'
exit 0
