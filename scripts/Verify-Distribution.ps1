[CmdletBinding()]
param(
    [ValidateSet('Local', 'Registry')][string]$Mode = 'Registry',
    [string]$Archive = '',
    [string]$ExpectedRevision = '',
    [int]$StartupTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dockerCommand = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
$release = Get-Content (Join-Path $repo 'distribution/release.json') -Raw | ConvertFrom-Json
if (-not $Archive) { $Archive = Join-Path $repo "dist/gamestats-$($release.version).zip" }
$Archive = (Resolve-Path $Archive).Path
$project = 'gamestats-package-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$work = Join-Path $repo ".cache/distribution/$project"
$package = Join-Path $work 'package'
New-Item -ItemType Directory -Path $package -Force | Out-Null
$effective = Join-Path $work 'compose.json'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$oldEnvironment = @{}
foreach ($key in @('DOCKER_CONFIG','DOCKER_CONTEXT','DOCKER_HOST','DOCKER_AUTH_CONFIG')) {
    $oldEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$failed = $false
$created = $false
$verified = $false
$localImages = @()
$checks = [System.Collections.Generic.List[string]]::new()
function Pass([string]$label) { $checks.Add($label); Write-Host "PASS: $label" }
function Docker([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $dockerCommand @Arguments 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { $_.ToString() }
        } | Tee-Object -FilePath (Join-Path $work 'docker.log') -Append
        $commandExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($commandExit -ne 0) { throw "Docker failed ($commandExit): $($Arguments -join ' ')" }
}
function Compose([string[]]$Arguments) { Docker (@('compose','-p',$project,'-f',$effective) + $Arguments) }
function Demo {
    $lines = @(Compose @('run','--rm','--no-deps','demo'))
    $summary = @($lines | Where-Object { $_ -like 'DEMO_RESULT *' })
    if ($summary.Count -ne 1 -or -not (($summary[0].Substring(12) | ConvertFrom-Json).passed)) { throw 'Demo did not produce a successful summary' }
    Write-Host ($lines -join "`n")
}
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $names = @($zip.Entries | ForEach-Object FullName | Sort-Object)
        if ($names.Count -ne 2 -or $names -cnotcontains 'README.md' -or $names -cnotcontains 'compose.yaml') { throw 'Unexpected ZIP content; only README.md and compose.yaml are allowed' }
    } finally { $zip.Dispose() }
    Expand-Archive -LiteralPath $Archive -DestinationPath $package
    $sourceCompose = Join-Path $package 'compose.yaml'
    $raw = @(Docker @('compose','--profile','demo','-f',$sourceCompose,'config','--format','json'))
    $config = ($raw -join "`n") | ConvertFrom-Json
    if (@($config.services.PSObject.Properties).Count -ne 4 -or $config.services.PSObject.Properties.Name -notcontains 'demo') { throw 'All four delivery services must be present' }
    foreach ($service in $config.services.PSObject.Properties) {
        if ($service.Value.PSObject.Properties.Name -contains 'build') { throw 'Package contains a build instruction' }
        if ($service.Value.image -notmatch '^ghcr\.io/sebastianvd32/gamestats-(go|scala|demo|panel):v\d+\.\d+\.\d+$') { throw 'Unexpected image reference' }
        if ($service.Value.PSObject.Properties.Name -contains 'volumes') {
            foreach ($volume in $service.Value.volumes) { if ($volume.type -ne 'volume') { throw 'Package contains a repository/host bind mount' } }
        }
    }
    Pass 'ZIP contains no sources; Compose uses only fixed registry images and named volumes'
    $config.PSObject.Properties.Remove('name')
    foreach ($volume in $config.volumes.PSObject.Properties) { $volume.Value.name = "${project}_$($volume.Name)" }
    foreach ($network in $config.networks.PSObject.Properties) { $network.Value.name = "${project}_$($network.Name)" }
    foreach ($service in $config.services.PSObject.Properties) {
        if ($service.Name -ne 'demo') {
            $port = if ($service.Name -eq 'go-ingestion') {8080} elseif ($service.Name -eq 'panel') {8090} else {8081}
            $service.Value.ports = @(@{target=$port; published='0'; host_ip='127.0.0.1'; protocol='tcp'})
        }
        if ($Mode -eq 'Local') {
            $tag = "${project}-$($service.Name):$($release.version)"
            $service.Value.image = $tag
            $service.Value.pull_policy = 'never'
            $localImages += $tag
            $context = switch ($service.Name) { 'go-ingestion' {Join-Path $repo 'go-ingestion'} 'scala-analysis' {Join-Path $repo 'scala-analytics'} 'demo' {$repo} 'panel' {$repo} }
            $dockerfile = switch ($service.Name) { 'demo' {Join-Path $repo 'distribution/demo/Dockerfile'} 'panel' {Join-Path $repo 'panel/Dockerfile'} default {Join-Path $context 'Dockerfile'} }
            Docker @('build','--platform','linux/amd64','-t',$tag,'-f',$dockerfile,$context)
        }
    }
    [System.IO.File]::WriteAllText($effective, ($config | ConvertTo-Json -Depth 100), $utf8)
    if ($Mode -eq 'Registry') {
        # Empty client credentials and mandatory pulls prevent local images from impersonating publication.
        $endpoint = (@(Docker @('context','inspect','--format','{{.Endpoints.docker.Host}}'))[-1]).Trim()
        if ($endpoint -notmatch '^(unix|npipe)://') { throw 'Anonymous verification currently requires a local Docker engine' }
        $plugins = @(Docker @('info','--format','{{json .ClientInfo.Plugins}}'))[-1] | ConvertFrom-Json
        $pluginDirectories = @($plugins | Where-Object { $_.Name -eq 'compose' } | ForEach-Object { Split-Path -Parent $_.Path })
        $guest = Join-Path $work 'anonymous-docker'
        New-Item -ItemType Directory -Path $guest -Force | Out-Null
        # Only the installed plugin directory is reused, never auths, credential helpers or personal config.
        $guestSettings = @{auths=@{}; cliPluginsExtraDirs=$pluginDirectories}
        [System.IO.File]::WriteAllText((Join-Path $guest 'config.json'), ($guestSettings | ConvertTo-Json -Depth 5), $utf8)
        $env:DOCKER_CONFIG=$guest; $env:DOCKER_CONTEXT=$null; $env:DOCKER_HOST=$endpoint; $env:DOCKER_AUTH_CONFIG=$null
        Compose @('--profile','demo','pull','--policy','always')
        $imageManifest = @()
        foreach ($service in $config.services.PSObject.Properties) {
            $digests = @(Docker @('image','inspect',$service.Value.image,'--format','{{json .RepoDigests}}'))[-1] | ConvertFrom-Json
            $imageRepository = $service.Value.image.Split(':')[0]
            $matching = @($digests | Where-Object { $_ -like "$imageRepository@sha256:*" })
            if ($matching.Count -eq 0) { throw 'Registry image has no matching repository digest' }
            $labels = @(Docker @('image','inspect',$service.Value.image,'--format','{{json .Config.Labels}}'))[-1] | ConvertFrom-Json
            $revision = if ($null -ne $labels -and $labels.PSObject.Properties.Name -contains 'org.opencontainers.image.revision') { $labels.'org.opencontainers.image.revision' } else { '' }
            if ($ExpectedRevision -and $revision -ne $ExpectedRevision) { throw 'Published image revision differs from the authorized workflow commit' }
            $imageManifest += @{image=$service.Value.image; digest=$matching[0]; revision=$revision; platform='linux/amd64'}
        }
        [System.IO.File]::WriteAllText((Join-Path $work 'images.json'), ($imageManifest | ConvertTo-Json -Depth 10), $utf8)
        $verified=$true
        Pass 'All four GHCR images pulled anonymously with mandatory registry access'
    } else { Pass 'LOCAL ONLY: images built under separate local names; registry publication is NOT verified' }
    $created=$true
    Compose @('up','-d','--wait','--wait-timeout',"$StartupTimeoutSeconds",'go-ingestion','scala-analysis','panel')
    Pass 'Source-free extracted package starts with healthy Go and Scala'
    $panelPort = (@(Compose @('port','panel','8090'))[-1]).Trim().Split(':')[-1]
    $panelUrl = "http://127.0.0.1:$panelPort"
    $python = if (Get-Command python3 -ErrorAction SilentlyContinue) {'python3'} else {'python'}
    & $python (Join-Path $repo 'scripts/test_panel_http.py') $panelUrl (Join-Path $work 'panel-http.json')
    if ($LASTEXITCODE -ne 0) { throw 'Panel HTTP acceptance failed' }
    if ($env:PLAYWRIGHT_MODULE) {
        node (Join-Path $repo 'scripts/test_panel_browser.cjs') $panelUrl (Join-Path $work 'browser')
        if ($LASTEXITCODE -ne 0) { throw 'Panel browser acceptance failed' }
        Pass 'Real browser demos, filters, results, reload and error display pass'
    }
    Compose @('restart','panel')
    Compose @('up','-d','--wait','panel')
    $panelPort = (@(Compose @('port','panel','8090'))[-1]).Trim().Split(':')[-1]
    & $python (Join-Path $repo 'scripts/test_panel_http.py') "http://127.0.0.1:$panelPort" (Join-Path $work 'panel-http.json') --verify-history
    if ($LASTEXITCODE -ne 0) { throw 'Panel history did not survive restart' }
    Pass 'Panel real API, unique runs, comparisons and persisted history pass'
    Demo
    Demo
    Pass 'Full demo passes twice without duplicated results or accumulated scores'
    Compose @('restart','go-ingestion','scala-analysis')
    # Restart may reassign dynamic host ports; demo uses stable internal service names.
    Demo
    $port = (@(Compose @('port','scala-analysis','8081'))[-1]).Trim().Split(':')[-1]
    $result = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v1/matches/demo-chess-1" -TimeoutSec 15
    if ($result.totalMoves -ne 5 -or $result.totalCaptures -ne 1) { throw 'Manual result endpoint returned unexpected data' }
    Pass 'After restarting both services, demo and host result endpoint still pass'
    Compose @('logs','--no-color') | Out-File (Join-Path $work 'services.log') -Encoding utf8
} catch {
    $failed=$true
    Write-Host "FAIL: $($_.Exception.Message)"
    Write-Host $_.ScriptStackTrace
    if ($created) { try { Compose @('logs','--no-color') | Out-File (Join-Path $work 'failure.log') -Encoding utf8 } catch { Write-Warning $_ } }
} finally {
    if ($created) { try { Compose @('--profile','demo','down','--volumes','--remove-orphans','--timeout','15') } catch { $failed=$true; Write-Host "Cleanup failed: $_" } }
    foreach ($key in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $oldEnvironment[$key], 'Process') }
    foreach ($tag in $localImages) {
        try { if (@(Docker @('image','ls','-q',$tag)).Count -gt 0) { Docker @('image','rm',$tag) } } catch { $failed=$true; Write-Host "Image cleanup failed: $_" }
    }
    $report = @{mode=$Mode; failed=$failed; registry_download_verified=$verified; passed=$checks.ToArray(); archive=$Archive; work=$work}
    [System.IO.File]::WriteAllText((Join-Path $work 'summary.json'), ($report | ConvertTo-Json -Depth 10), $utf8)
    Write-Host "Package verification: mode=$Mode failed=$failed registry_download_verified=$verified; evidence=$work"
}
if ($failed) { exit 1 }
exit 0
