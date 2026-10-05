[CmdletBinding()]
param(
    [string]$SiteDirectory = 'server-site',
    [string]$ReleaseSiteDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ReleaseSiteDirectory) {
    $ReleaseSiteDirectory = Join-Path (Split-Path $repoRoot -Parent) 'app-release-site'
}
$manifestPath = Join-Path $repoRoot "$SiteDirectory/dist/update.json"
$releaseDist = Join-Path $ReleaseSiteDirectory 'dist'
$deployScript = Join-Path $ReleaseSiteDirectory 'scripts/deploy-site.ps1'
$catalogPath = Join-Path $releaseDist 'apps.json'
foreach ($path in @($manifestPath, $deployScript, $catalogPath, (Join-Path $releaseDist 'index.html'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.package_name -ne 'com.example.medicalrecord') { throw 'Unexpected release package name.' }
if ($manifest.version_name -notmatch '^\d+\.\d+\.\d+$' -or [long]$manifest.version_code -lt 1) {
    throw 'Invalid release version.'
}
$fileName = "medical-record-v$($manifest.version_name).apk"
if ($manifest.download_url -ne "https://acupofttu.top/downloads/$fileName") { throw 'Unexpected release download URL.' }
$apkPath = Join-Path $repoRoot "$SiteDirectory/dist/downloads/$fileName"
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $manifest.sha256) { throw 'Prepared APK does not match the manifest SHA-256.' }

# Keep the multi-app homepage and the other applications in the catalog.
$catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $catalog.apps.'medical-record') { throw 'Medical record is missing from the apps catalog.' }
$entry = $catalog.apps.'medical-record'
$fields = @{
    versionName = $manifest.version_name
    versionCode = $manifest.version_code
    publishedAt = $manifest.published_at
    releaseNotes = @($manifest.release_notes)
    downloadUrl = $manifest.download_url
    sha256 = $hash
    apiUrl = 'https://acupofttu.top/apps/medical-record/update.json'
}
foreach ($name in $fields.Keys) { $entry | Add-Member -NotePropertyName $name -NotePropertyValue $fields[$name] -Force }
$catalog | Add-Member -NotePropertyName updated_at -NotePropertyValue $manifest.published_at -Force
$manifest | Add-Member -NotePropertyName app_id -NotePropertyValue 'medical-record' -Force
$appDir = Join-Path $releaseDist 'apps/medical-record'
$downloads = Join-Path $releaseDist 'downloads'
New-Item -ItemType Directory -Path $appDir, $downloads -Force | Out-Null
Copy-Item -LiteralPath $apkPath -Destination (Join-Path $downloads $fileName) -Force
$encoding = [System.Text.UTF8Encoding]::new($false)
$manifestJson = ($manifest | ConvertTo-Json -Depth 20) + [Environment]::NewLine
[System.IO.File]::WriteAllText((Join-Path $appDir 'update.json'), $manifestJson, $encoding)
[System.IO.File]::WriteAllText((Join-Path $releaseDist 'update.json'), $manifestJson, $encoding)
[System.IO.File]::WriteAllText($catalogPath, ($catalog | ConvertTo-Json -Depth 20) + [Environment]::NewLine, $encoding)
& $deployScript -DistDirectory $releaseDist -SpecificApkFileName $fileName
