[CmdletBinding()]
param(
    [string]$VersionName,
    [long]$VersionCode,
    [string[]]$ReleaseNotes,
    [long]$MinimumSupportedVersionCode,
    [switch]$ForceUpdate,
    [string]$SiteDirectory = 'server-site',
    [string]$PublishOrigin = 'https://acupofttu.top',
    [switch]$PrepareOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidGradlePath = Join-Path $repoRoot 'app/build.gradle.kts'
$siteRoot = Join-Path $repoRoot $SiteDirectory
$manifestPath = Join-Path $siteRoot 'dist/update.json'
$signingPath = Join-Path $repoRoot 'keystore.properties'
$downloadDirectory = Join-Path $siteRoot 'dist/downloads'
$siteOrigin = $PublishOrigin.TrimEnd('/')
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

# These features were previously present only in a debug APK. Stop if the
# release checkout has lost them before changing versions or uploading files.
$requiredSourceFeatures = @(
    @{ File = 'RecognitionUi.kt'; Marker = 'onDismissRequest = { if (!progress.active) onDismiss() }' },
    @{ File = 'Recognition.kt'; Marker = 'suggested_category' },
    @{ File = 'Screens.kt'; Marker = 'report.suggestedCategory.isNotBlank()' },
    @{ File = 'Trends.kt'; Marker = 'val chartPoints = points' }
)
foreach ($feature in $requiredSourceFeatures) {
    $sourcePath = Join-Path $repoRoot "app/src/main/java/com/example/medicalrecord/$($feature.File)"
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf) -or
        -not [System.IO.File]::ReadAllText($sourcePath).Contains($feature.Marker)) {
        throw "Release source is missing a required feature in $($feature.File): $($feature.Marker)"
    }
}

function Read-PropertiesFile([string]$Path) {
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*[#!]' -or $line -notmatch '^\s*([^=\s]+)\s*=(.*)$') { continue }
        $values[$Matches[1]] = $Matches[2].Trim()
    }
    return $values
}

function Find-AndroidSdk {
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) {
        $localProperties = Join-Path $repoRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties) {
            $line = [System.IO.File]::ReadAllLines($localProperties) | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
            if ($line -match '^\s*sdk\.dir\s*=(.*)$') {
                $sdk = $Matches[1].Trim().Replace('\:', ':').Replace('\\', '\')
            }
        }
    }
    if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) {
        throw 'Android SDK was not found. Set ANDROID_SDK_ROOT or configure local.properties.'
    }
    return (Resolve-Path -LiteralPath $sdk).Path
}

if (-not (Test-Path -LiteralPath $signingPath)) {
    throw 'Release signing is not configured. Copy keystore.properties.example to keystore.properties and enter the existing release key details.'
}
$signing = Read-PropertiesFile $signingPath
foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $signing.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($signing[$key]) -or $signing[$key] -eq 'REPLACE_ME') {
        throw "Release signing is missing '$key' in keystore.properties."
    }
}
$keystore = if ([System.IO.Path]::IsPathRooted($signing.storeFile)) { $signing.storeFile } else { Join-Path $repoRoot $signing.storeFile }
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) { throw "Signing keystore not found: $keystore" }
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Release manifest not found: $manifestPath" }

$manifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$gradleOriginal = [System.IO.File]::ReadAllText($androidGradlePath)
$versionCodeMatch = [regex]::Match($gradleOriginal, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
$versionNameMatch = [regex]::Match($gradleOriginal, '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) { throw 'Could not read the app version from app/build.gradle.kts.' }
$currentCode = [long]$versionCodeMatch.Groups[1].Value
$currentName = $versionNameMatch.Groups[1].Value

if (-not $VersionCode) { $VersionCode = $currentCode + 1 }
if ($VersionCode -le $currentCode -or $VersionCode -le [long]$manifest.version_code) {
    throw "VersionCode must be greater than the current app and published versions ($currentCode / $($manifest.version_code))."
}
if (-not $VersionName) {
    $semver = [regex]::Match($currentName, '^(\d+)\.(\d+)\.(\d+)$')
    if (-not $semver.Success) { throw 'Pass -VersionName because the current version is not a three-part numeric version.' }
    $VersionName = '{0}.{1}.{2}' -f $semver.Groups[1].Value, $semver.Groups[2].Value, ([long]$semver.Groups[3].Value + 1)
}
if ($VersionName -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') { throw 'VersionName may contain only letters, numbers, dot, underscore, and hyphen.' }
if ($MinimumSupportedVersionCode -eq 0) { $MinimumSupportedVersionCode = [long]$manifest.minimum_supported_version_code }
if ($MinimumSupportedVersionCode -lt 1 -or $MinimumSupportedVersionCode -gt $VersionCode) { throw 'MinimumSupportedVersionCode must be between 1 and the new VersionCode.' }
if (-not $ReleaseNotes -or -not $ReleaseNotes.Count) {
    $ReleaseNotes = @()
    while ($true) {
        $note = Read-Host 'Enter a release note (leave blank to finish)'
        if ([string]::IsNullOrWhiteSpace($note)) { break }
        $ReleaseNotes += $note.Trim()
    }
}
if (-not $ReleaseNotes.Count -or @($ReleaseNotes | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count) {
    throw 'Enter at least one non-empty release note.'
}
if ($manifest.package_name -ne 'com.example.medicalrecord') { throw 'The release manifest package name does not match the Android app.' }

$fileName = "medical-record-v$VersionName.apk"
$apkDestination = Join-Path $downloadDirectory $fileName
if (Test-Path -LiteralPath $apkDestination) { throw "A release APK already exists at $apkDestination" }

$gradleUpdated = [regex]::Replace($gradleOriginal, '(?m)^([ \t]*versionCode[ \t]*=[ \t]*)\d+', { param($m) $m.Groups[1].Value + $VersionCode }, 1)
$gradleUpdated = [regex]::Replace($gradleUpdated, '(?m)^([ \t]*versionName[ \t]*=[ \t]*)"[^"]+"', { param($m) $m.Groups[1].Value + '"' + $VersionName + '"' }, 1)
if ($gradleUpdated -eq $gradleOriginal) { throw 'Could not update the app version in app/build.gradle.kts.' }
[System.IO.File]::WriteAllText($androidGradlePath, $gradleUpdated, $utf8NoBom)

try {
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') ':app:assembleRelease' '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} catch {
    [System.IO.File]::WriteAllText($androidGradlePath, $gradleOriginal, $utf8NoBom)
    throw
}

$apkSource = Join-Path $repoRoot 'app/build/outputs/apk/release/app-release.apk'
try {
    if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) { throw "Gradle did not produce $apkSource" }
    $sdk = Find-AndroidSdk
    $buildTools = Join-Path $sdk 'build-tools'
    $apksigner = Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending | ForEach-Object {
        $candidate = Join-Path $_.FullName 'apksigner.bat'
        if (-not (Test-Path -LiteralPath $candidate)) { $candidate = Join-Path $_.FullName 'apksigner.exe' }
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    } | Select-Object -First 1
    if (-not $apksigner) { throw "Android apksigner was not found under $buildTools" }
    & $apksigner verify --verbose $apkSource
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
} catch {
    [System.IO.File]::WriteAllText($androidGradlePath, $gradleOriginal, $utf8NoBom)
    throw
}

$originalManifest = [System.IO.File]::ReadAllText($manifestPath)
$apkCopied = $false
try {
    New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
    Copy-Item -LiteralPath $apkSource -Destination $apkDestination
    $apkCopied = $true
    $sha256 = (Get-FileHash -LiteralPath $apkDestination -Algorithm SHA256).Hash.ToLowerInvariant()

    $manifest.version_name = $VersionName
    $manifest.version_code = $VersionCode
    $manifest.minimum_supported_version_code = $MinimumSupportedVersionCode
    $manifest.force_update = [bool]$ForceUpdate
    $manifest.published_at = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    $manifest.release_notes = @($ReleaseNotes)
    $manifest.download_url = "$siteOrigin/downloads/$fileName"
    $manifest.sha256 = $sha256

    $manifestTemp = "$manifestPath.tmp"
    $json = $manifest | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($manifestTemp, $json + [Environment]::NewLine, $utf8NoBom)
    Move-Item -LiteralPath $manifestTemp -Destination $manifestPath -Force
} catch {
    if ($apkCopied -and (Test-Path -LiteralPath $apkDestination)) { Remove-Item -LiteralPath $apkDestination -Force }
    [System.IO.File]::WriteAllText($manifestPath, $originalManifest, $utf8NoBom)
    [System.IO.File]::WriteAllText($androidGradlePath, $gradleOriginal, $utf8NoBom)
    throw
}

Write-Host "Release $VersionName (versionCode $VersionCode) is ready."
Write-Host "APK: $apkDestination"
Write-Host "SHA-256: $sha256"
if ($PrepareOnly) {
    Write-Host "Release files are prepared in $siteRoot."
} else {
    try {
        & (Join-Path $PSScriptRoot 'deploy-prepared-release.ps1') -SiteDirectory $SiteDirectory
    } catch {
        Write-Host 'Release files remain prepared locally. Run deploy-prepared-release.ps1 to retry the server upload.'
        throw
    }
}
