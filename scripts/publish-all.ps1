[CmdletBinding()]
param(
    [string]$VersionName,
    [long]$VersionCode,
    [string[]]$ReleaseNotes,
    [long]$MinimumSupportedVersionCode,
    [switch]$ForceUpdate,
    [switch]$Resume,
    [switch]$SkipSite
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$manifestPath = Join-Path $repoRoot 'server-site/dist/update.json'
$gradlePath = Join-Path $repoRoot 'app/build.gradle.kts'

function Invoke-Git([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & git -C $repoRoot @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "git $($Arguments -join ' ') failed (exit code $exitCode): $output" }
    return @($output | Where-Object { $_ -is [string] })
}

function Read-PreparedRelease {
    $manifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $gradle = [System.IO.File]::ReadAllText($gradlePath)
    $version = [regex]::Match($gradle, '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$')
    $code = [regex]::Match($gradle, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
    if (-not $version.Success -or -not $code.Success -or
        $version.Groups[1].Value -ne $manifest.version_name -or
        [long]$code.Groups[1].Value -ne [long]$manifest.version_code) {
        throw 'The Android version does not match update.json.'
    }
    $apkPath = Join-Path $repoRoot "server-site/dist/downloads/medical-record-v$($manifest.version_name).apk"
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw "Prepared APK not found: $apkPath" }
    $hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $manifest.sha256.ToLowerInvariant()) { throw 'Prepared APK does not match update.json.' }
    $apkModifiedAt = (Get-Item -LiteralPath $apkPath).LastWriteTimeUtc
    $newerSource = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'app/src/main') -Recurse -File |
        Where-Object { $_.LastWriteTimeUtc -gt $apkModifiedAt } |
        Select-Object -First 1
    if ($newerSource) { throw "App source changed after the APK was built: $($newerSource.FullName)" }
    return $manifest
}

if ((Invoke-Git @('branch', '--show-current') | Select-Object -First 1) -ne 'main') {
    throw 'Release from the main branch.'
}
$staged = @(Invoke-Git @('diff', '--cached', '--name-only'))
if ($staged.Count) { throw 'Commit or unstage existing staged files before publishing.' }

$currentManifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$committedManifestText = (Invoke-Git @('show', 'HEAD:server-site/dist/update.json')) -join "`n"
$committedVersion = [regex]::Match($committedManifestText, '"version_code"\s*:\s*(\d+)')
if (-not $committedVersion.Success) { throw 'Could not read the committed version code.' }
$prepared = $Resume -or ([long]$currentManifest.version_code -gt [long]$committedVersion.Groups[1].Value)
if ($prepared) {
    if (@($PSBoundParameters.Keys | Where-Object { $_ -in @('VersionName', 'VersionCode', 'ReleaseNotes', 'MinimumSupportedVersionCode', 'ForceUpdate') }).Count) {
        throw 'Version options cannot be used when resuming an already prepared release.'
    }
    $manifest = Read-PreparedRelease
    Write-Host "Reusing prepared release $($manifest.version_name)."
} else {
    $buildOptions = @{ PrepareOnly = $true }
    foreach ($key in @('VersionName', 'VersionCode', 'ReleaseNotes', 'MinimumSupportedVersionCode', 'ForceUpdate')) {
        if ($PSBoundParameters.ContainsKey($key)) { $buildOptions[$key] = $PSBoundParameters[$key] }
    }
    & (Join-Path $PSScriptRoot 'publish-release.ps1') @buildOptions
    $manifest = Read-PreparedRelease
}

$siteError = $null
if (-not $SkipSite) {
    try {
        & (Join-Path $PSScriptRoot 'deploy-prepared-release.ps1')
    } catch {
        $siteError = $_
        Write-Warning "Website upload failed; continuing with Gitee: $($_.Exception.Message)"
    }
}

Invoke-Git @('add', '-A', '--', 'README.md', 'app/src/main', 'app/build.gradle.kts', 'server-site/README.md', 'server-site/dist/update.json', 'scripts') | Out-Null
$toCommit = @(Invoke-Git @('diff', '--cached', '--name-only'))
if ($toCommit.Count) {
    Invoke-Git @('commit', '-m', "Publish $($manifest.version_name)") | ForEach-Object { Write-Host $_ }
}
Invoke-Git @('push', 'gitee', 'HEAD:main') | Out-Null
& (Join-Path $PSScriptRoot 'publish-gitee-release.ps1')

if ($siteError) {
    throw "Gitee Release is published, but the website upload failed: $($siteError.Exception.Message). Run deploy-prepared-release.ps1 to retry the website."
}
if ($SkipSite) {
    Write-Host "Release $($manifest.version_name) published to Gitee."
} else {
    Write-Host "Release $($manifest.version_name) published to Gitee and the website."
}
