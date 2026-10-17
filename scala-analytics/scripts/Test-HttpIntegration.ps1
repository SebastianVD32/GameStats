# Compatibility entry point. The complete verifier owns only isolated test resources.
$ErrorActionPreference = 'Stop'
$verifier = Join-Path $PSScriptRoot '../../scripts/Verify-Integration.ps1'
& $verifier
exit $LASTEXITCODE