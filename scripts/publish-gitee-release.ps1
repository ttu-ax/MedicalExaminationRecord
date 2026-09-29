[CmdletBinding()]
param(
    [string]$Owner = 'afeng66',
    [string]$Repository = 'MedicalExaminationRecord',
    [string]$Remote = 'gitee'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$manifestPath = Join-Path $repoRoot 'server-site/dist/update.json'
$gradlePath = Join-Path $repoRoot 'app/build.gradle.kts'

function Invoke-Git([string[]]$Arguments) {
    # Windows PowerShell 5.1 treats normal Git progress on stderr as a
    # NativeCommandError when the script-wide preference is Stop.
    $ErrorActionPreference = 'Continue'
    $output = & git -C $repoRoot @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "git $($Arguments -join ' ') failed (exit code $exitCode): $output" }
    return @($output | Where-Object { $_ -is [string] })
}

function Invoke-GiteeApi([System.Net.Http.HttpClient]$Client, [string]$Method, [string]$Path, [System.Net.Http.HttpContent]$Content = $null) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "https://gitee.com/api/v5$Path")
    if ($Content) { $request.Content = $Content }
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode -and [int]$response.StatusCode -ne 404) {
                throw "Gitee API $Method $Path returned HTTP $([int]$response.StatusCode): $body"
            }
            return @{ Status = [int]$response.StatusCode; Body = $body }
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Missing update manifest: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.version_name -notmatch '^\d+\.\d+\.\d+$') { throw 'The release version must be a three-part numeric version.' }
$version = $manifest.version_name
$releaseName = "$($manifest.app_name) $version"
if ([string]::IsNullOrWhiteSpace($manifest.app_name)) { throw 'update.json has no app name.' }
$tag = "v$version"
$apkName = "medical-record-v$version.apk"
$apkPath = Join-Path $repoRoot "server-site/dist/downloads/$apkName"
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw "Missing prepared APK: $apkPath" }
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $manifest.sha256.ToLowerInvariant()) { throw 'APK SHA-256 does not match update.json.' }
$gradle = Get-Content -LiteralPath $gradlePath -Raw
if ($gradle -notmatch "(?m)^\s*versionName\s*=\s*`"$([regex]::Escape($version))`"\s*$" -or
    $gradle -notmatch "(?m)^\s*versionCode\s*=\s*$($manifest.version_code)\s*$") {
    throw 'Android version does not match update.json.'
}
$releaseNotes = @($manifest.release_notes | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if (-not $releaseNotes.Count) { throw 'update.json has no release notes.' }

$head = (Invoke-Git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
$remoteUrl = (Invoke-Git @('remote', 'get-url', $Remote) | Select-Object -First 1).Trim()
if ($remoteUrl -notmatch "^https://gitee\.com/$([regex]::Escape($Owner))/$([regex]::Escape($Repository))(?:\.git)?$" -and
    $remoteUrl -notmatch "^git@gitee\.com:$([regex]::Escape($Owner))/$([regex]::Escape($Repository))(?:\.git)?$") {
    throw "Remote '$Remote' does not point to the expected Gitee repository: $remoteUrl"
}
$workingChanges = @(Invoke-Git @('status', '--porcelain', '--', 'app/build.gradle.kts', 'server-site/dist/update.json'))
if ($workingChanges.Count) { throw 'Commit the Android version and update.json before publishing.' }
$remoteMain = @(Invoke-Git @('ls-remote', $Remote, 'refs/heads/main'))
if (-not $remoteMain.Count -or ($remoteMain[0] -split '\s+')[0] -ne $head) {
    throw 'Push the release commit to Gitee main before publishing.'
}

$envPath = Join-Path $PSScriptRoot '.env'
$secureToken = $null
$token = $null
$saveToken = $false
if (-not [string]::IsNullOrWhiteSpace($env:GITEE_TOKEN)) {
    $token = $env:GITEE_TOKEN.Trim()
} else {
    if (Test-Path -LiteralPath $envPath -PathType Leaf) {
        foreach ($line in [System.IO.File]::ReadAllLines($envPath, [System.Text.Encoding]::UTF8)) {
            if ($line -match '^\s*GITEE_TOKEN_DPAPI=(.+)\s*$') {
                $secureToken = $Matches[1].Trim() | ConvertTo-SecureString
                break
            }
            if ($line -match '^\s*GITEE_TOKEN=(.+)\s*$') {
                $token = $Matches[1].Trim().Trim('"', "'")
                break
            }
        }
    }
    if (-not $secureToken -and [string]::IsNullOrWhiteSpace($token)) {
        $secureToken = Read-Host 'Gitee personal access token (saved encrypted after success)' -AsSecureString
        $saveToken = $true
    }
    if ($secureToken) {
        $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
        try {
            $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
        }
    }
}
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Gitee token is required.' }

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
try {
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    $token = $null

    $encodedOwner = [uri]::EscapeDataString($Owner)
    $encodedRepo = [uri]::EscapeDataString($Repository)
    $encodedTag = [uri]::EscapeDataString($tag)
    $basePath = "/repos/$encodedOwner/$encodedRepo/releases"
    $existing = Invoke-GiteeApi $client 'GET' "$basePath/tags/$encodedTag"

    $localTag = @(Invoke-Git @('tag', '--list', $tag))
    $tagCommit = $null
    if ($localTag.Count) {
        $tagCommit = (Invoke-Git @('rev-list', '-n', '1', $tag) | Select-Object -First 1).Trim()
    }
    $remoteTag = @(Invoke-Git @('ls-remote', '--tags', $Remote, "refs/tags/$tag", "refs/tags/$tag^{}"))
    $remoteCommit = $null
    if ($remoteTag.Count) {
        $peeled = @($remoteTag | Where-Object { $_ -like "*refs/tags/$tag^{}" })
        $remoteCommit = if ($peeled.Count) { ($peeled[0] -split '\s+')[0] } else { ($remoteTag[0] -split '\s+')[0] }
    }
    if (($localTag.Count -gt 0) -ne ($remoteTag.Count -gt 0)) { throw "Local and remote tag $tag do not agree." }
    if ($tagCommit -and $remoteCommit -ne $tagCommit) { throw "Local and remote tag $tag point to different commits." }
    if ($tagCommit -and $tagCommit -ne $head) {
        if ($existing.Status -eq 404 -or [string]::IsNullOrWhiteSpace($existing.Body) -or $existing.Body.Trim() -eq 'null') {
            throw "Tag $tag points to an older commit, but no matching Gitee Release exists."
        }
        Invoke-Git @('merge-base', '--is-ancestor', $tagCommit, 'HEAD') | Out-Null
    }
    if (-not $localTag.Count) { Invoke-Git @('tag', $tag) | Out-Null }
    if (-not $remoteTag.Count) { Invoke-Git @('push', $Remote, "refs/tags/$tag") | Out-Null }

    if ($existing.Status -eq 404 -or [string]::IsNullOrWhiteSpace($existing.Body) -or $existing.Body.Trim() -eq 'null') {
        $fields = New-Object 'System.Collections.Generic.List[System.Collections.Generic.KeyValuePair[string,string]]'
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('tag_name', $tag))
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('name', $releaseName))
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('body', (($releaseNotes | ForEach-Object { "- $_" }) -join "`n")))
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('target_commitish', 'main'))
        $content = [System.Net.Http.FormUrlEncodedContent]::new($fields)
        $created = Invoke-GiteeApi $client 'POST' $basePath $content
        $release = $created.Body | ConvertFrom-Json
    } else {
        $release = $existing.Body | ConvertFrom-Json
    }
    if (-not $release.id) { throw 'Gitee did not return a Release ID.' }
    if ($release.name -ne $releaseName) {
        $fields = New-Object 'System.Collections.Generic.List[System.Collections.Generic.KeyValuePair[string,string]]'
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('tag_name', $tag))
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('name', $releaseName))
        $fields.Add([System.Collections.Generic.KeyValuePair[string,string]]::new('body', [string]$release.body))
        $content = [System.Net.Http.FormUrlEncodedContent]::new($fields)
        $updated = Invoke-GiteeApi $client 'PATCH' "$basePath/$($release.id)" $content
        $release = $updated.Body | ConvertFrom-Json
        if ($release.name -ne $releaseName) { throw 'Gitee did not update the Release title.' }
        Write-Host "Release title corrected: $releaseName"
    }

    $assetPath = "$basePath/$($release.id)/attach_files"
    $assets = Invoke-GiteeApi $client 'GET' $assetPath
    if ($assets.Status -eq 404) { throw 'Could not list Gitee Release attachments.' }
    $existingAssets = @($assets.Body | ConvertFrom-Json)
    $matchingAsset = @($existingAssets | Where-Object { $_.name -eq $apkName })
    $expectedApkUrl = "https://gitee.com/$Owner/$Repository/releases/download/$tag/$apkName"
    if ($matchingAsset.Count) {
        if ($matchingAsset[0].browser_download_url -ne $expectedApkUrl) { throw 'Existing APK download URL does not match this Release.' }
        Write-Host "APK already attached: $($matchingAsset[0].browser_download_url)"
    } else {
        $fileStream = [System.IO.File]::OpenRead($apkPath)
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        try {
            $fileContent = [System.Net.Http.StreamContent]::new($fileStream)
            $multipart.Add($fileContent, 'file', $apkName)
            $uploaded = Invoke-GiteeApi $client 'POST' $assetPath $multipart
            $asset = $uploaded.Body | ConvertFrom-Json
            if ($asset.browser_download_url -ne $expectedApkUrl) { throw 'Gitee returned an unexpected APK download URL.' }
            Write-Host "APK: $($asset.browser_download_url)"
        } finally {
            $multipart.Dispose()
            $fileStream.Dispose()
        }
    }

    $matchingManifest = @($existingAssets | Where-Object { $_.name -eq 'update.json' })
    $expectedManifestUrl = "https://gitee.com/$Owner/$Repository/releases/download/$tag/update.json"
    if ($matchingManifest.Count) {
        if ($matchingManifest[0].browser_download_url -ne $expectedManifestUrl) { throw 'Existing update manifest URL does not match this Release.' }
        Write-Host "Update manifest already attached: $expectedManifestUrl"
    } else {
        $manifest.download_url = $expectedApkUrl
        $manifestJson = $manifest | ConvertTo-Json -Depth 20
        $manifestBytes = [System.Text.Encoding]::UTF8.GetBytes($manifestJson)
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        try {
            $manifestContent = [System.Net.Http.ByteArrayContent]::new($manifestBytes)
            $manifestContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
            $multipart.Add($manifestContent, 'file', 'update.json')
            $uploaded = Invoke-GiteeApi $client 'POST' $assetPath $multipart
            $asset = $uploaded.Body | ConvertFrom-Json
            if ($asset.browser_download_url -ne $expectedManifestUrl) { throw 'Gitee returned an unexpected update manifest URL.' }
            Write-Host "Update manifest: $($asset.browser_download_url)"
        } finally {
            $multipart.Dispose()
        }
    }
    Write-Host "Release: https://gitee.com/$Owner/$Repository/releases/tag/$tag"
    Write-Host "SHA-256: $hash"
    if ($saveToken) {
        $encryptedToken = ConvertFrom-SecureString $secureToken
        [System.IO.File]::WriteAllText($envPath, "GITEE_TOKEN_DPAPI=$encryptedToken`r`n", [System.Text.UTF8Encoding]::new($false))
        Write-Host "Gitee token saved for this Windows user: $envPath"
    }
} finally {
    $client.Dispose()
}
