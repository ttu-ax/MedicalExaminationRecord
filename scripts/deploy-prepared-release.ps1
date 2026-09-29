[CmdletBinding()]
param(
    [string]$SiteDirectory = 'server-site'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$siteRoot = Join-Path $repoRoot $SiteDirectory
$dist = Join-Path $siteRoot 'dist'
$manifestPath = Join-Path $dist 'update.json'
$indexPath = Join-Path $dist 'index.html'
$keyPath = Join-Path $env:USERPROFILE '.ssh\medical_record_deploy_ed25519'
$remoteTarget = 'meddeploy@43.142.143.128'
$stage = '/home/meddeploy/medical-record-site-staging'
$webRoot = '/www/wwwroot/MedicalExaminationRecord'
$siteOrigin = 'https://acupofttu.top'

foreach ($path in @($manifestPath, $indexPath, $keyPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
}

$manifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if ($manifest.package_name -ne 'com.example.medicalrecord') { throw 'The manifest package name does not match the Android app.' }
if ([long]$manifest.version_code -lt 1) { throw 'The manifest version code is invalid.' }
if ($manifest.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'The manifest SHA-256 is invalid.' }

$downloadUri = [uri]$manifest.download_url
$fileName = [System.IO.Path]::GetFileName($downloadUri.AbsolutePath)
if ($downloadUri.Scheme -ne 'https' -or $downloadUri.Host -ne 'acupofttu.top' -or
    $downloadUri.UserInfo -or $downloadUri.Query -or $downloadUri.Fragment -or
    $fileName -notmatch '^medical-record-v[0-9A-Za-z._-]+\.apk$' -or
    $downloadUri.AbsolutePath -ne "/downloads/$fileName") {
    throw 'The manifest download URL must point to an APK on acupofttu.top.'
}

$apkPath = Join-Path (Join-Path $dist 'downloads') $fileName
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw "Release APK not found: $apkPath" }
$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sha256 -ne $manifest.sha256.ToLowerInvariant()) { throw 'The release APK does not match the manifest SHA-256.' }
$manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()

$sshOptions = @('-i', $keyPath, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=12')
& ssh.exe @sshOptions -p 22 $remoteTarget "mkdir -p '$stage/downloads'"
if ($LASTEXITCODE -ne 0) { throw 'Could not connect to the deployment account.' }

$scpOptions = @('-q', '-i', $keyPath, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-P', '22')
& scp.exe @scpOptions $apkPath "${remoteTarget}:$stage/downloads/$fileName"
if ($LASTEXITCODE -ne 0) { throw 'Could not upload the release APK.' }
& scp.exe @scpOptions $indexPath "${remoteTarget}:$stage/index.html"
if ($LASTEXITCODE -ne 0) { throw 'Could not upload the release page.' }
& scp.exe @scpOptions $manifestPath "${remoteTarget}:$stage/update.json"
if ($LASTEXITCODE -ne 0) { throw 'Could not upload the release manifest.' }

$remoteScript = @"
set -e
printf '%s  %s\n' '$sha256' '$stage/downloads/$fileName' | sha256sum -c -
mkdir -p '$webRoot/downloads'
chmod 755 '$webRoot/downloads'
install -m 644 '$stage/downloads/$fileName' '$webRoot/downloads/.$fileName.tmp'
mv -f '$webRoot/downloads/.$fileName.tmp' '$webRoot/downloads/$fileName'
install -m 644 '$stage/index.html' '$webRoot/.index.html.tmp'
mv -f '$webRoot/.index.html.tmp' '$webRoot/index.html'
install -m 644 '$stage/update.json' '$webRoot/.update.json.tmp'
mv -f '$webRoot/.update.json.tmp' '$webRoot/update.json'
"@
& ssh.exe @sshOptions -p 22 $remoteTarget $remoteScript
if ($LASTEXITCODE -ne 0) { throw 'The server could not install the prepared release.' }

$verifiedLocallyOnServer = $false
try {
    $live = Invoke-RestMethod -Uri "$siteOrigin/update.json?verify=$($manifest.version_code)" -TimeoutSec 20
    $head = Invoke-WebRequest -Uri $manifest.download_url -Method Head -UseBasicParsing -TimeoutSec 20
    if ([int]$head.StatusCode -ne 200) { throw 'The live APK download is unavailable.' }
} catch {
    Write-Warning 'Public HTTPS could not be checked from this computer; checking HTTPS through the server loopback address.'
    $remoteHashOutput = & ssh.exe @sshOptions -p 22 $remoteTarget "curl -fsS --resolve acupofttu.top:443:127.0.0.1 '$siteOrigin/update.json?verify=$($manifest.version_code)' | sha256sum"
    if ($LASTEXITCODE -ne 0) { throw 'The server could not serve the live update manifest over HTTPS.' }
    $remoteManifestSha256 = [regex]::Match(($remoteHashOutput -join ''), '^[a-fA-F0-9]{64}').Value.ToLowerInvariant()
    if ($remoteManifestSha256 -ne $manifestSha256) { throw 'The live update manifest does not match the uploaded file.' }
    $live = $manifest
    $headStatus = & ssh.exe @sshOptions -p 22 $remoteTarget "curl -fsS -o /dev/null -w '%{http_code}' -I --resolve acupofttu.top:443:127.0.0.1 '$($manifest.download_url)'"
    if ($LASTEXITCODE -ne 0 -or ($headStatus -join '') -ne '200') { throw 'The server could not serve the live APK over HTTPS.' }
    $verifiedLocallyOnServer = $true
}
if ([long]$live.version_code -ne [long]$manifest.version_code -or
    $live.sha256.ToLowerInvariant() -ne $sha256 -or
    $live.download_url -ne $manifest.download_url) {
    throw 'The live update manifest does not match the prepared release.'
}
Write-Host "Published $($manifest.version_name) (versionCode $($manifest.version_code)) to $siteOrigin."
Write-Host "APK SHA-256: $sha256"
if ($verifiedLocallyOnServer) { Write-Warning 'Public access from this computer remains unverified; the server served both URLs over HTTPS.' }
