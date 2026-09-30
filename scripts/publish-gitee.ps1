[CmdletBinding()]
param(
    [string]$VersionName,
    [long]$VersionCode,
    [string[]]$ReleaseNotes,
    [long]$MinimumSupportedVersionCode,
    [switch]$ForceUpdate,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$remoteUrl = 'https://gitee.com/afeng66/MedicalExaminationRecord.git'

if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'keystore.properties') -PathType Leaf)) {
    throw 'Configure keystore.properties with the existing release signing key before publishing.'
}

$hasToken = -not [string]::IsNullOrWhiteSpace($env:GITEE_TOKEN)
foreach ($envPath in @((Join-Path $repoRoot '.env'), (Join-Path $PSScriptRoot '.env'))) {
    if ($hasToken -or -not (Test-Path -LiteralPath $envPath -PathType Leaf)) { continue }
    $hasToken = [bool]([System.IO.File]::ReadAllText($envPath) -match '(?m)^\s*GITEE_TOKEN(?:_DPAPI)?\s*=\s*\S+')
}
if (-not $hasToken) {
    throw 'Add GITEE_TOKEN=your_token to the ignored .env file in the repository root before publishing.'
}

$remoteNames = @(& git -C $repoRoot remote)
if ($LASTEXITCODE -ne 0) { throw 'Could not list git remotes.' }
if ($remoteNames -notcontains 'gitee') {
    & git -C $repoRoot remote add gitee $remoteUrl
    if ($LASTEXITCODE -ne 0) { throw 'Could not add the Gitee git remote.' }
    Write-Host "Added Gitee remote: $remoteUrl"
} else {
    $currentRemote = & git -C $repoRoot remote get-url gitee
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the Gitee git remote.' }
    if ($currentRemote -notin @($remoteUrl, 'https://gitee.com/afeng66/MedicalExaminationRecord', 'git@gitee.com:afeng66/MedicalExaminationRecord.git', 'git@gitee.com:afeng66/MedicalExaminationRecord')) {
        throw "The gitee remote points to a different repository: $currentRemote"
    }
}

$options = @{ SkipSite = $true }
foreach ($key in @('VersionName', 'VersionCode', 'ReleaseNotes', 'MinimumSupportedVersionCode', 'ForceUpdate', 'Resume')) {
    if ($PSBoundParameters.ContainsKey($key)) { $options[$key] = $PSBoundParameters[$key] }
}
& (Join-Path $PSScriptRoot 'publish-all.ps1') @options
