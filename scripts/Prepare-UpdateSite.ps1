[CmdletBinding()]
param(
    [string]$ApkPath = 'app/build/outputs/apk/release/douyin-skipad-release.apk',
    [string]$NotesPath = 'docs/releases/v1.5.0.md',
    [string]$OutputDirectory = 'build/update-site',
    [string]$AndroidSdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function WorkspacePath([string]$path) {
    $resolved = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $path))
    if (-not $resolved.StartsWith($workspaceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Output and input paths must be inside this project.'
    }
    return $resolved
}
$apk = WorkspacePath $ApkPath
$output = WorkspacePath $OutputDirectory
$notes = Get-Content -LiteralPath (WorkspacePath $NotesPath) -Raw -Encoding UTF8
if (-not $AndroidSdk) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $workspaceRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
    $AndroidSdk = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':')
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$windowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$aapt = Join-Path $buildTools.FullName $(if ($windowsHost) { 'aapt.exe' } else { 'aapt' })
$signer = Join-Path $buildTools.FullName $(if ($windowsHost) { 'apksigner.bat' } else { 'apksigner' })
$signatureOutput = (& $signer verify --print-certs $apk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$expectedSignerFile = Join-Path $workspaceRoot '.github/release-signing-sha256.txt'
if (Test-Path -LiteralPath $expectedSignerFile) {
    $expectedSigner = (Get-Content -LiteralPath $expectedSignerFile -Raw).Trim().ToLowerInvariant()
    $actualSigner = [regex]::Match($signatureOutput, 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})').Groups[1].Value.ToLowerInvariant()
    if ($actualSigner -ne $expectedSigner) {
        $digestLines = ([regex]::Matches($signatureOutput, '(?im)^.*SHA-256.*$') | ForEach-Object { $_.Value }) -join '; '
        throw "APK signer mismatch. Expected certificate: $expectedSigner; parsed certificate: $actualSigner; public digest output: $digestLines"
    }
}
$badging = (& $aapt dump xmltree $apk AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK metadata.' }
$package = [regex]::Match($badging, 'package="([^"]+)"')
$codeMatch = [regex]::Match($badging, 'android:versionCode\([^)]*\)=\(type 0x10\)0x([0-9a-fA-F]+)')
$nameMatch = [regex]::Match($badging, 'android:versionName\([^)]*\)="([^"]+)"')
if (-not $package.Success -or $package.Groups[1].Value -ne 'com.example.skipad' -or -not $codeMatch.Success -or -not $nameMatch.Success) { throw 'Unexpected APK metadata.' }
$version = $nameMatch.Groups[1].Value
if ($version -notmatch '^\d{1,6}\.\d{1,6}\.\d{1,6}$') { throw 'Expected a stable three-part version.' }
$bytes = (Get-Item -LiteralPath $apk).Length
if ($bytes -gt 25MB) { throw 'APK exceeds the Cloudflare Pages 25 MiB limit.' }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$relativeApk = "releases/v$version/douyin-skipad-release.apk"
$releaseDirectory = Join-Path $output "releases/v$version"
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $output $relativeApk) -Force
$manifest = [ordered]@{
    schemaVersion = 1; packageName = 'com.example.skipad'; versionName = $version
    versionCode = [Convert]::ToInt64($codeMatch.Groups[1].Value, 16); apk = $relativeApk
    size = $bytes; sha256 = $hash; notes = $notes.Trim()
}
$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $output 'update.json'), ($manifest | ConvertTo-Json -Depth 5), $utf8)
$safeNotes = [Net.WebUtility]::HtmlEncode($notes.Trim())
$page = @"
<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>抖音跳广告助手 · 下载</title>
<style>body{font:16px/1.7 system-ui,sans-serif;max-width:680px;margin:48px auto;padding:0 24px;background:#f6f8f7;color:#17251e}main{background:white;padding:28px;border-radius:20px}a{color:#16745c}a.download{display:inline-block;background:#16745c;color:white;padding:12px 22px;border-radius:12px;text-decoration:none}pre{white-space:pre-wrap;overflow-wrap:anywhere}small{display:block;overflow-wrap:anywhere;color:#55665b}@media(prefers-color-scheme:dark){body{background:#101a15;color:#e3eee7}main{background:#1b2921}small{color:#afc1b4}}</style>
<main><h1>抖音跳广告助手</h1><p>版本 $version · Android 7.0 及以上</p>
<p><a class="download" href="$relativeApk" download>下载 Android 安装包</a></p>
<p>覆盖安装可保留设置。安装按手机系统提示确认。</p><small>SHA256：$hash</small>
<h2>更新说明</h2><pre>$safeNotes</pre>
<p><a href="https://github.com/16201323/douyin-skipad/releases">GitHub 历史版本</a></p></main></html>
"@
[IO.File]::WriteAllText((Join-Path $output 'index.html'), $page, $utf8)
[IO.File]::WriteAllText((Join-Path $output '404.html'), '<!doctype html><meta charset="utf-8"><title>文件不存在</title><p>文件不存在，请返回下载首页。</p>', $utf8)
$headers = @'
/update.json
  Content-Type: application/json; charset=utf-8
  Cache-Control: no-store, no-cache, must-revalidate
  X-Content-Type-Options: nosniff
/releases/*
  Content-Type: application/vnd.android.package-archive
  Cache-Control: public, max-age=31536000, immutable
  X-Content-Type-Options: nosniff
/*
  Referrer-Policy: no-referrer
'@
[IO.File]::WriteAllText((Join-Path $output '_headers'), $headers, $utf8)
$zipPath = "$output.zip"
Compress-Archive -Path (Join-Path $output '*') -DestinationPath $zipPath -Force
[PSCustomObject]@{Version=$version; VersionCode=$manifest.versionCode; Bytes=$bytes; SHA256=$hash; SiteDirectory=$output; UploadZip=$zipPath}
