[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string] $Notes,

    [switch] $Publish,

    [switch] $Prerelease,

    [string] $Target = "HEAD"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$VersionFile = Join-Path $Root "version.properties"
$ReleaseIdentityFile = Join-Path $Root "release-identity.properties"
$NotesPath = if ([IO.Path]::IsPathRooted($Notes)) { $Notes } else { Join-Path $Root $Notes }

function Read-VersionProperties {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($VersionFile, [Text.Encoding]::UTF8)) {
        if ($line -match "^(?<key>[A-Z0-9_]+)=(?<value>.+)$") {
            $values[$Matches["key"]] = $Matches["value"].Trim()
        }
    }
    if (-not $values.ContainsKey("SOIM_VERSION_NAME") -or -not $values.ContainsKey("SOIM_VERSION_CODE")) {
        throw "version.properties is incomplete"
    }
    return $values
}

function Read-KeyValueProperties {
    param([Parameter(Mandatory = $true)][string] $Path)
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        if ($line -match "^(?<key>[A-Z0-9_]+)=(?<value>.*)$") {
            $values[$Matches["key"]] = $Matches["value"].Trim()
        }
    }
    return $values
}

function Require-Environment {
    param([Parameter(Mandatory = $true)][string] $Name)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing required environment variable: $Name"
    }
    return $value.Trim()
}

function Invoke-Captured {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "$FilePath failed with exit code $code`n$($lines -join [Environment]::NewLine)"
    }
    return $lines -join [Environment]::NewLine
}

if (-not [IO.File]::Exists($NotesPath)) {
    throw "Release notes file does not exist: $NotesPath"
}
if ([string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($NotesPath, [Text.Encoding]::UTF8))) {
    throw "Release notes must not be empty"
}

$localSigningProperties = Join-Path $Root "keystore.properties"
if (-not [IO.File]::Exists($localSigningProperties)) {
    $storeFile = Require-Environment "SOIM_SIGNING_STORE_FILE"
    [void] (Require-Environment "SOIM_SIGNING_STORE_PASSWORD")
    [void] (Require-Environment "SOIM_SIGNING_KEY_ALIAS")
    [void] (Require-Environment "SOIM_SIGNING_KEY_PASSWORD")
    if (-not [IO.File]::Exists($storeFile)) {
        throw "Release keystore does not exist: $storeFile"
    }
}
$identity = Read-KeyValueProperties $ReleaseIdentityFile
foreach ($required in @("SOIM_OFFICIAL_PACKAGE_ID", "SOIM_FIRST_OFFICIAL_VERSION", "SOIM_FIRST_OFFICIAL_TAG", "SOIM_OFFICIAL_CERT_SHA256")) {
    if (-not $identity.ContainsKey($required)) { throw "release-identity.properties is missing $required" }
}
$expectedCert = ([string] $identity["SOIM_OFFICIAL_CERT_SHA256"]) -replace "[^0-9A-Fa-f]", ""
if ($expectedCert.Length -ne 64) {
    throw "Tracked official certificate SHA-256 is not configured"
}
$environmentCertValue = [Environment]::GetEnvironmentVariable("SOIM_SIGNING_CERT_SHA256")
if ($null -eq $environmentCertValue) { $environmentCertValue = "" }
$environmentCert = $environmentCertValue -replace "[^0-9A-Fa-f]", ""
if ($environmentCert.Length -gt 0 -and $environmentCert.ToUpperInvariant() -cne $expectedCert.ToUpperInvariant()) {
    throw "SOIM_SIGNING_CERT_SHA256 does not match the tracked official certificate"
}

$properties = Read-VersionProperties
$version = [string] $properties["SOIM_VERSION_NAME"]
$code = [int] $properties["SOIM_VERSION_CODE"]
$tag = "v$version"
if ($version -eq [string] $identity["SOIM_FIRST_OFFICIAL_VERSION"] -and $tag -ne [string] $identity["SOIM_FIRST_OFFICIAL_TAG"]) {
    throw "First official release tag does not match release identity"
}
if ($version -eq [string] $identity["SOIM_FIRST_OFFICIAL_VERSION"] -and -not $Prerelease) {
    throw "The first official release must initially be published with -Prerelease"
}

Push-Location $Root
try {
    & (Join-Path $Root "gradlew.bat") clean :app:testDebugUnitTest :app:testReleaseUnitTest `
        :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "Gradle release gate failed" }
}
finally {
    Pop-Location
}

$apk = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
if (-not [IO.File]::Exists($apk)) {
    throw "Signed release APK is missing: $apk"
}
$releaseAsset = Join-Path ([IO.Path]::GetDirectoryName($apk)) "soim-v$version-release.apk"
[IO.File]::Copy($apk, $releaseAsset, $true)

$sdkLine = Select-String -LiteralPath (Join-Path $Root "local.properties") -Pattern "^\s*sdk\.dir\s*=" |
    Select-Object -First 1
if ($null -eq $sdkLine) { throw "local.properties does not contain sdk.dir" }
$sdk = (($sdkLine.Line -split "=", 2)[1].Trim()) -replace "\\:", ":" -replace "\\\\", "\"
$tools = Get-ChildItem -LiteralPath (Join-Path $sdk "build-tools") -Directory |
    Where-Object { $_.Name -match "^[0-9]+\.[0-9]+\.[0-9]+$" } |
    Sort-Object { [Version] $_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $tools) { throw "Android build-tools are missing" }
$aapt = Join-Path $tools.FullName "aapt.exe"
$apksigner = Join-Path $tools.FullName "apksigner.bat"

$badging = Invoke-Captured $aapt @("dump", "badging", $apk)
foreach ($token in @(
    "package: name='$([string] $identity["SOIM_OFFICIAL_PACKAGE_ID"])'",
    "versionName='$version'",
    "versionCode='$code'",
    "sdkVersion:'29'",
    "targetSdkVersion:'36'"
)) {
    if (-not $badging.Contains($token)) { throw "APK metadata mismatch: $token" }
}

$signature = Invoke-Captured $apksigner @("verify", "--print-certs", $apk)
if ($signature -match "CN=Android Debug") { throw "Refusing to publish a Debug-signed APK" }
$actualCertLine = @($signature -split "`r?`n") |
    Where-Object { $_ -match "Signer #1 certificate SHA-256 digest:" } |
    Select-Object -First 1
if ($null -eq $actualCertLine) { throw "Unable to read signer SHA-256 digest" }
$actualCert = (($actualCertLine -split ":", 2)[1]) -replace "[^0-9A-Fa-f]", ""
if ($actualCert.ToUpperInvariant() -cne $expectedCert.ToUpperInvariant()) {
    throw "Release signer does not match SOIM_SIGNING_CERT_SHA256"
}

$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToUpperInvariant()
Write-Output "VERSION=$version"
Write-Output "VERSION_CODE=$code"
Write-Output "APK=$releaseAsset"
Write-Output "SHA256=$sha256"
Write-Output "SIGNER_SHA256=$($actualCert.ToUpperInvariant())"

if (-not $Publish) {
    Write-Output "PUBLISH=SKIPPED"
    return
}
if (-not $PSCmdlet.ShouldProcess("github.com/SoulQAQ/so-image-manager", "Create $tag release")) {
    return
}

[void] (Invoke-Captured "gh" @("auth", "status"))
$existing = & gh release view $tag --repo "SoulQAQ/so-image-manager" 2>$null
if ($LASTEXITCODE -eq 0) { throw "GitHub Release already exists: $tag" }
$releaseArguments = @(
    "release", "create", $tag, $releaseAsset,
    "--repo", "SoulQAQ/so-image-manager",
    "--target", $Target,
    "--title", "SoIM $tag",
    "--notes-file", $NotesPath
)
if ($Prerelease) { $releaseArguments += "--prerelease" }
[void] (Invoke-Captured "gh" $releaseArguments)
Write-Output "PUBLISHED_RELEASE=https://github.com/SoulQAQ/so-image-manager/releases/tag/$tag"
